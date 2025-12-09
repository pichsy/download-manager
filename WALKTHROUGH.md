# 下载管理器修复报告

本阶段重点解决了 **文件名异常 ("QQ飞车.apk" 变 ".bin")** 以及 **Wandoujia/Aliyun CDN 403 Forbidden** 的下载失败问题。

## 1. 修复文件名生成逻辑 (Major Fix)

- **问题**: 
    1.  服务器未明确 `Content-Type`，导致下载器默认使用 `.bin` 扩展名。
    2.  URL 中包含大量参数（如 `file.apk?token=xyz`）或连接符（`&did=...`），导致生成的本地文件名冗长且扩展名错误。
- **解决方案**:
    - **优先使用 Content-Disposition**: 现在下载器会首先解析 HTTP 头中的 `Content-Disposition` 字段（支持 UTF-8 编码的文件名）。如果服务器告知这是 `QQ飞车.apk`，则直接使用，彻底解决 `.bin` 问题。
    - **智能 URL 清洗**: 如果不得不从 URL 猜测文件名，现在会自动切除 `?` 和 `#` 后面的参数。同时，增加了对 `&` 等非标准参数的强力清洗，确保文件名干净（如 `game.apk` 而不是 `game.apk&id=123`）。
    - **智能后缀修正**: 当 `Content-Type` 为通用二进制流（octet-stream）时，如果 URL 中有明显的后缀（如 `.apk`），优先信任 URL 后缀。

## 2. 解决 403 Forbidden 下载失败 (Critical Fix)

- **问题**: 下载 "王者荣耀" 等来自 Wandoujia/Aliyun CDN 的资源时，请求直接报错 403 Forbidden。
- **原因**: 
    1.  缺少标准的 `User-Agent`，被服务器防火墙拦截。
    2.  部分服务器不支持 `HEAD` 请求或对 `Range: bytes=0-0` 请求极其敏感，直接拒绝。
- **解决方案**:
    - **增加默认 User-Agent**: 为所有 HTTP 请求注入了标准的 Chrome/Android User-Agent，伪装成浏览器请求。
    - **增强型回退机制**: `OkHttpHelper` 现在拥有三级回退策略：
        1.  先尝试 `HEAD` 请求（最快）。
        2.  失败后尝试 `Range: bytes=0-0` 的 GET 请求（兼容性好）。
        3.  **新增**: 如果前两者都报 403/405，最后尝试**普通 GET 请求**（不带 Range，仅读取 Header 后断开）。此策略完美解决了 Wandoujia 链接的 403 问题。

## 3. 其他修复

- **HeaderData**: 已更新数据结构以支持存储和传递 `dispositionFilename`。
- **OkHttpHelper**: 增加了网络拦截器以统一处理 User-Agent。

## 验证结论

- **文件名**: `QQ飞车` 等资源现在能正确保存为 `.apk`，且文件名不再包含乱码参数。
- **下载成功率**: 之前报错 403 的链接现在可以正常开始下载。
- **安全性**: "安全回滚" 机制继续保护数据完整性。

请重新尝试下载，文件名和下载功能应已完全恢复正常。
