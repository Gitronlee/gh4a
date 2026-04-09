请生成一片文档# gh4a - GitHub Android Client

基于 [slapperwan/gh4a](https://github.com/slapperwan/gh4a)（原 OctoDroid）优化的 GitHub Android 客户端。

**本版维护者**: ronlee (ronlee@live.cn)
**代码仓库**: [ronlee/gh4a](https://github.com/ronlee/gh4a)
**上游仓库**: [slapperwan/gh4a](https://github.com/slapperwan/gh4a)

---

## 上游简介

gh4a 是一款功能完善的 GitHub 第三方 Android 客户端，支持：

- **仓库管理** — 浏览分支/标签、查看 Pull Request、Issue、贡献者
- **用户动态** — 查看活动流、关注/取关用户、浏览组织成员
- **Issue 管理** — 创建/编辑/关闭 Issue、评论、标签和里程碑管理
- **代码浏览** — 语法高亮查看源码、彩色 Diff 查看 Commit 变更
- **Gist** — 浏览公开 Gist 及其内容
- **探索 GitHub** — 公开动态、热门趋势、GitHub Blog
- **通知** — 支持多账户通知推送
- **搜索** — 仓库、用户、代码搜索

---

## 本版本优化改进

### 文件编辑与提交功能增强

- **文件在线编辑与提交**：支持在移动端直接编辑仓库文件并提交到 GitHub
  - 修复了 Fine-grained Token 权限导致的 HTTP 404 保存失败问题（需使用 Classic Token + `repo` scope）
  - 提交前自动刷新文件 SHA，防止并发修改冲突
  - 登录界面增加 Token 权限说明提示

- **图片压缩导入**：编辑器支持插入本地图片并以 Base64 Data URI 格式内联到 Markdown 中
  - 三阶段压缩管线（降采样 → 缩放 → 自适应质量），输出上限 500KB
  - 解决大图插入时 EditText 卡顿问题
  - 初始大小限制 5MB，支持常见图片格式

---

## 构建

```bash
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

构建前需配置 GitHub OAuth 凭据（`client.properties`），详见上游项目说明。

---

## 开源依赖

| 库 | 说明 |
|---|---|
| [android-gif-drawable](https://github.com/koral--/android-gif-drawable) | GIF 动图渲染 |
| [AndroidSVG](https://github.com/BigBadaboom/androidsvg) | SVG 支持 |
| [GitHubSdk](https://github.com/maniac103/GitHubSdk) | GitHub API |
| [Retrofit](https://github.com/square/retrofit) | HTTP 客户端 |
| [RxJava / RxAndroid](https://github.com/ReactiveX/RxJava) | 响应式编程 |

完整列表参见上游 README。

---

## 致谢

- **原作者**: Azwan Adli, Danny Baumann (Gh4a)
- **主要贡献者**: maniac103, kageiit, Tunous, ARoiD, cketti, zquestz 等众多社区贡献者
