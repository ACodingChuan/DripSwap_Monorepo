# 设计令牌（Design Tokens）

> 仅作前端主题参考；实现以 src/shared/ui 的变体/尺寸为准。

## 颜色

- Primary（主色）：#1E66F5 （示例：与原型蓝一致或接近）
- Success（成功）：#22C55E
- Warning（警告）：#F59E0B
- Error（错误）：#EF4444
- Background（浅）：#F6F8FB
- Background（深，可选）：#0B1220
- Text 主：#0F172A
- Text 次：#475569

## 圆角

- Card：24px
- Button：10–12px（保持统一）
- Input/Select：与 Button 保持一致的高度与圆角

## 间距与尺寸

- Spacing 阶梯：8 / 12 / 16 / 24 / 32
- 版心宽度：1200–1280px 居中
- 阴影：卡片中等柔和，悬浮时稍加强
- 动效：200–250ms ease-out（hover/展开/对话框）

## 可访问性（a11y）

- 对比度 ≥ 4.5:1
- 焦点 ring 清晰；键盘可达
- 所有交互控件具备 label / aria-label
