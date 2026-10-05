package com.example.poker.service;

import com.example.poker.dto.AccountViews;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Service
public class AccountService {
    private static final int FORMAT_VERSION = 2;
    private static final int PROFILE_HISTORY_LIMIT = 100;
    private static final char[] LOGIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Pattern CUSTOM_LOGIN_CODE = Pattern.compile("[A-Z0-9]{12}");
    private static final Pattern SHORT_LOGIN_CODE = Pattern.compile("\\d{4}");

    private final ObjectMapper mapper;
    private final PokerSettings settings;
    private final Path storageFile;
    private final Map<UUID, StoredAccount> accounts = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AccountService(ObjectMapper mapper, PokerSettings settings,
                          @Value("${poker.accounts-file:}") String configuredFile) {
        this(mapper, settings, resolveStorageFile(configuredFile));
    }

    AccountService(ObjectMapper mapper, PokerSettings settings, Path storageFile) {
        this.mapper = mapper;
        this.settings = settings;
        this.storageFile = storageFile;
        load();
    }

    public synchronized AccountViews.AccountSession create(String rawNickname) {
        String nickname = normalizeNickname(rawNickname);
        if (accounts.values().stream().anyMatch(account -> account.nickname().equalsIgnoreCase(nickname)))
            throw new IllegalArgumentException("该昵称已绑定账号，请使用跨设备登录码登录");

        Instant now = Instant.now();
        String loginCode = generateLoginCode();
        StoredAccount account = new StoredAccount(UUID.randomUUID(), UUID.randomUUID(), nickname,
                settings.values().totalChips(), hashLoginCode(loginCode), now, now, List.of());
        accounts.put(account.id(), account);
        persist();
        return new AccountViews.AccountSession(account.id(), account.token(), loginCode, profileOf(account));
    }

    public synchronized AccountViews.AccountSession login(String rawNickname, String rawLoginCode) {
        String nickname = normalizeNickname(rawNickname);
        String loginCode = normalizeLoginCode(rawLoginCode);
        StoredAccount account = accounts.values().stream()
                .filter(candidate -> candidate.nickname().equalsIgnoreCase(nickname))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("账号或跨设备登录码不正确"));
        if (account.loginCodeHash() == null || !secureEquals(account.loginCodeHash(), hashLoginCode(loginCode)))
            throw new IllegalArgumentException("账号或跨设备登录码不正确");
        return new AccountViews.AccountSession(account.id(), account.token(), formatLoginCode(loginCode),
                profileOf(account));
    }

    public synchronized AccountViews.LoginCode rotateLoginCode(UUID accountId, UUID accountToken) {
        return rotateLoginCode(accountId, accountToken, null);
    }

    /**
     * 更换跨设备登录码。requestedCode 为空时随机生成 12 位（保持既有行为）；
     * 传入则使用指定码 —— 4 位纯数字或 12 位字母数字组合，且未被其他账号占用。
     */
    public synchronized AccountViews.LoginCode rotateLoginCode(UUID accountId, UUID accountToken,
                                                               String requestedCode) {
        return applyLoginCode(authenticate(accountId, accountToken), requestedCode);
    }

    public synchronized List<AccountViews.AdminAccount> adminList() {
        return accounts.values().stream().map(this::adminView).toList();
    }

    /** 管理员改账号：nickname / chips / loginCode 任一为空表示保持不变。 */
    public synchronized AccountViews.AdminAccountUpdate adminUpdate(UUID accountId, String rawNickname,
                                                                    Integer chips, String rawLoginCode) {
        StoredAccount account = require(accountId);
        String nickname = rawNickname == null || rawNickname.isBlank()
                ? account.nickname() : normalizeNickname(rawNickname);
        if (!nickname.equalsIgnoreCase(account.nickname()) && nicknameTaken(nickname, accountId))
            throw new IllegalArgumentException("该昵称已被其他账号使用");
        String loginCode = rawLoginCode == null || rawLoginCode.isBlank()
                ? null : requireCustomLoginCode(rawLoginCode);
        if (loginCode != null) ensureCodeUnused(loginCode, accountId);
        StoredAccount updated = new StoredAccount(account.id(), account.token(), nickname,
                chips == null ? account.chips() : chips,
                loginCode == null ? account.loginCodeHash() : hashLoginCode(loginCode),
                account.createdAt(), Instant.now(), account.hands());
        accounts.put(account.id(), updated);
        persist();
        return new AccountViews.AdminAccountUpdate(adminView(updated),
                loginCode == null ? null : formatLoginCode(loginCode));
    }

    public synchronized void adminDelete(UUID accountId) {
        require(accountId);
        accounts.remove(accountId);
        persist();
    }

    private AccountViews.AdminAccount adminView(StoredAccount account) {
        return new AccountViews.AdminAccount(account.id(), account.nickname(), account.chips(),
                account.createdAt(), account.lastSeenAt(), account.hands().size());
    }

    private boolean nicknameTaken(String nickname, UUID selfId) {
        return accounts.values().stream()
                .anyMatch(other -> !other.id().equals(selfId) && other.nickname().equalsIgnoreCase(nickname));
    }

    private AccountViews.LoginCode applyLoginCode(StoredAccount account, String requestedCode) {
        String loginCode = requestedCode == null || requestedCode.isBlank()
                ? generateLoginCode() : formatLoginCode(requireCustomLoginCode(requestedCode));
        ensureCodeUnused(loginCode, account.id());
        StoredAccount updated = new StoredAccount(account.id(), account.token(), account.nickname(),
                account.chips(), hashLoginCode(loginCode), account.createdAt(), Instant.now(), account.hands());
        accounts.put(account.id(), updated);
        persist();
        return new AccountViews.LoginCode(loginCode);
    }

    /** 自定义登录码：4 位纯数字，或 12 位字母数字组合；随机生成仍是 12 位。 */
    private String requireCustomLoginCode(String rawLoginCode) {
        String code = normalizeLoginCode(rawLoginCode);
        if (SHORT_LOGIN_CODE.matcher(code).matches()) return code;
        if (code.length() != 12)
            throw new IllegalArgumentException("跨设备登录码需要 4 位数字或 12 位字母数字组合");
        if (!CUSTOM_LOGIN_CODE.matcher(code).matches())
            throw new IllegalArgumentException("跨设备登录码只能包含字母和数字");
        return code;
    }

    private void ensureCodeUnused(String loginCode, UUID selfId) {
        String hash = hashLoginCode(loginCode);
        boolean taken = accounts.values().stream()
                .anyMatch(other -> !other.id().equals(selfId) && hash.equals(other.loginCodeHash()));
        if (taken) throw new IllegalArgumentException("该跨设备登录码已被其他账号使用");
    }

    public synchronized AccountViews.Profile profile(UUID accountId, UUID accountToken) {
        StoredAccount account = authenticate(accountId, accountToken);
        StoredAccount touched = new StoredAccount(account.id(), account.token(), account.nickname(),
                account.chips(), account.loginCodeHash(), account.createdAt(), Instant.now(), account.hands());
        accounts.put(account.id(), touched);
        return profileOf(touched);
    }

    public synchronized StoredIdentity identity(UUID accountId, UUID accountToken) {
        StoredAccount account = authenticate(accountId, accountToken);
        return new StoredIdentity(account.id(), account.nickname(), account.chips());
    }

    public synchronized void updateBalance(UUID accountId, int chips) {
        if (accountId == null) return;
        if (chips < 0) throw new IllegalArgumentException("账号筹码不能为负数");
        StoredAccount account = require(accountId);
        if (account.chips() == chips) return;
        accounts.put(accountId, new StoredAccount(account.id(), account.token(), account.nickname(), chips,
                account.loginCodeHash(), account.createdAt(), Instant.now(), account.hands()));
        persist();
    }

    public synchronized void recordHand(UUID accountId, HandResult result) {
        if (accountId == null) return;
        StoredAccount account = require(accountId);
        boolean duplicate = account.hands().stream().anyMatch(hand -> hand.tableId().equals(result.tableId())
                && hand.handNumber() == result.handNumber());
        if (duplicate) return;

        List<StoredHand> hands = new ArrayList<>(account.hands());
        hands.add(new StoredHand(UUID.randomUUID(), Instant.now(), result.tableId(), result.tableName(),
                result.handNumber(), result.mode(), normalizeResult(result.result()), result.netChips(),
                result.endingChips()));
        accounts.put(accountId, new StoredAccount(account.id(), account.token(), account.nickname(),
                result.endingChips(), account.loginCodeHash(), account.createdAt(), Instant.now(), List.copyOf(hands)));
        persist();
    }

    private String normalizeResult(String result) {
        if ("WIN".equals(result) || "TIE".equals(result) || "LOSS".equals(result)) return result;
        throw new IllegalArgumentException("无效牌局结果");
    }

    private StoredAccount authenticate(UUID accountId, UUID accountToken) {
        StoredAccount account = require(accountId);
        if (accountToken == null || !account.token().equals(accountToken))
            throw new IllegalArgumentException("账号身份已失效，请使用原浏览器继续");
        return account;
    }

    private StoredAccount require(UUID accountId) {
        StoredAccount account = accounts.get(accountId);
        if (account == null) throw new IllegalArgumentException("账号不存在");
        return account;
    }

    private AccountViews.Profile profileOf(StoredAccount account) {
        List<StoredHand> newest = account.hands().stream()
                .sorted(Comparator.comparing(StoredHand::playedAt).reversed())
                .limit(PROFILE_HISTORY_LIMIT).toList();
        List<AccountViews.HandRecord> history = newest.stream().map(this::handView).toList();
        return new AccountViews.Profile(account.id(), account.nickname(), account.chips(), account.createdAt(),
                stats(account.hands(), ignored -> true), stats(account.hands(), hand -> "AI".equals(hand.mode())),
                stats(account.hands(), hand -> "HUMAN".equals(hand.mode())), history);
    }

    private AccountViews.HandRecord handView(StoredHand hand) {
        return new AccountViews.HandRecord(hand.id(), hand.playedAt(), hand.tableId(), hand.tableName(),
                hand.handNumber(), hand.mode(), hand.result(), hand.netChips(), hand.endingChips());
    }

    private AccountViews.ModeStats stats(List<StoredHand> hands, Predicate<StoredHand> filter) {
        List<StoredHand> selected = hands.stream().filter(filter).toList();
        int wins = (int) selected.stream().filter(hand -> "WIN".equals(hand.result())).count();
        int ties = (int) selected.stream().filter(hand -> "TIE".equals(hand.result())).count();
        int losses = selected.size() - wins - ties;
        int net = selected.stream().mapToInt(StoredHand::netChips).sum();
        double winRate = selected.isEmpty() ? 0 : wins / (double) selected.size();
        return new AccountViews.ModeStats(selected.size(), wins, ties, losses, net, winRate);
    }

    private String normalizeNickname(String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        if (nickname.isBlank() || nickname.length() > 16)
            throw new IllegalArgumentException("昵称需要 1–16 个字符");
        return nickname;
    }

    private String generateLoginCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int index = 0; index < 12; index++)
            raw.append(LOGIN_CODE_ALPHABET[random.nextInt(LOGIN_CODE_ALPHABET.length)]);
        return formatLoginCode(raw.toString());
    }

    private String normalizeLoginCode(String rawLoginCode) {
        String code = rawLoginCode == null ? "" : rawLoginCode.replace("-", "")
                .replace(" ", "").trim().toUpperCase(Locale.ROOT);
        if (code.isBlank()) throw new IllegalArgumentException("请输入跨设备登录码");
        return code;
    }

    private String formatLoginCode(String rawLoginCode) {
        String code = rawLoginCode.replace("-", "");
        if (code.length() != 12) return code;
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12);
    }

    private String hashLoginCode(String loginCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeLoginCode(loginCode).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成跨设备登录码", exception);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void load() {
        if (storageFile == null || !Files.isRegularFile(storageFile)) return;
        try {
            Snapshot snapshot = mapper.readValue(storageFile.toFile(), Snapshot.class);
            if (snapshot.accounts() != null)
                snapshot.accounts().forEach(account -> accounts.put(account.id(), account));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("账号数据读取失败: " + storageFile, exception);
        }
    }

    private void persist() {
        if (storageFile == null) return;
        try {
            Path parent = storageFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),
                    new Snapshot(FORMAT_VERSION, List.copyOf(accounts.values())));
            try {
                Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("账号数据保存失败: " + storageFile, exception);
        }
    }

    private static Path resolveStorageFile(String configuredFile) {
        if (configuredFile != null && !configuredFile.isBlank()) return Path.of(configuredFile.trim());
        String stateDirectory = System.getenv("STATE_DIRECTORY");
        if (stateDirectory != null && !stateDirectory.isBlank())
            return Path.of(stateDirectory, "accounts.json");
        return Path.of("data", "accounts.json");
    }

    public record StoredIdentity(UUID id, String nickname, int chips) {}
    public record HandResult(UUID tableId, String tableName, long handNumber, String mode,
                             String result, int netChips, int endingChips) {}

    private record Snapshot(int version, List<StoredAccount> accounts) {}
    private record StoredAccount(UUID id, UUID token, String nickname, int chips, String loginCodeHash,
                                 Instant createdAt, Instant lastSeenAt, List<StoredHand> hands) {}
    private record StoredHand(UUID id, Instant playedAt, UUID tableId, String tableName,
                              long handNumber, String mode, String result,
                              int netChips, int endingChips) {}
}
