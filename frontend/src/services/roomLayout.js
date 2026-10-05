export function roomLayout(width, height, compact) {
  const sideSpace = compact ? 76 : 160
  const verticalSpace = compact ? 120 : 130
  const availableHeight = Math.max(260, height - verticalSpace)
  const tableWidth = Math.max(0, Math.min(compact ? 300 : 850, width - sideSpace,
    compact ? Infinity : availableHeight * 1.78))
  const tableHeight = compact ? Math.min(tableWidth / 0.7, availableHeight) : tableWidth / 1.78
  return { tableWidth, tableHeight, sceneWidth: tableWidth + sideSpace, sceneHeight: tableHeight + verticalSpace }
}
