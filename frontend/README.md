# OSH Text2SQL Frontend

开发启动：

```bash
npm install
npm run dev -- --host 0.0.0.0
```

开发模式默认通过 Vite 把 `/api` 代理到本机 `http://127.0.0.1:9100`。

生产部署时推荐继续使用同源 `/api`，不要在前端代码里写死后端域名或端口。
