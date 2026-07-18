export function displayRank(value) {
  const rank = value.slice(0, -1)
  return rank === 'T' ? '10' : rank
}
