import { Hono, type Context, type Next } from "hono";

type Env = {
  Bindings: {
    KV: KVNamespace;
    GHOST_TOKEN: string;
  };
};

type AppContext = Context<Env>;

// POST /clip 请求体及 KV 存储格式（与 sync-protocol spec 统一）
interface ClipRecord {
  device_id: string;
  text: string;
  hash: string;
  timestamp: number;
}

// PUT /register 请求体及 KV 存储格式
interface PeerRecord {
  device_id: string;
  device_type: "android" | "mac";
}

const MAX_CONTENT_LENGTH = 1_048_576; // 1MB

const app = new Hono<Env>();

// Bearer token 认证中间件
app.use("*", async (c: AppContext, next: Next) => {
  const auth = c.req.header("Authorization");
  if (!auth || auth !== `Bearer ${c.env.GHOST_TOKEN}`) {
    return c.json({ error: "unauthorized" }, 401);
  }
  await next();
});

// POST /clip - 写入剪贴板内容到 KV
app.post("/clip", async (c: AppContext) => {
  let body: { device_id: string; text: string; hash: string; timestamp: number };
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid JSON" }, 400);
  }

  if (!body.text || !body.hash || !body.device_id || !body.timestamp) {
    return c.json({ error: "missing fields: device_id, text, hash, timestamp" }, 400);
  }

  if (body.text.length > MAX_CONTENT_LENGTH) {
    return c.json({ error: "text exceeds 1MB limit" }, 413);
  }

  // Hash 比对去重：如果 KV 中已有相同 hash 则跳过写入
  const existing = await c.env.KV.get("clip:latest", "json") as Pick<ClipRecord, "hash"> | null;
  if (existing && existing.hash === body.hash) {
    return c.json({ status: "duplicate", hash: body.hash });
  }

  const record: ClipRecord = {
    device_id: body.device_id,
    text: body.text,
    hash: body.hash,
    timestamp: body.timestamp,
  };
  await c.env.KV.put("clip:latest", JSON.stringify(record));

  return c.json({ status: "ok", hash: body.hash });
});

// GET /clip - 读取最新剪贴板内容，支持 last_hash 比对
app.get("/clip", async (c: AppContext) => {
  const lastHash = c.req.query("last_hash");

  const raw = await c.env.KV.get("clip:latest");
  if (!raw) {
    // KV 中无数据，返回 304
    return c.body(null, 304);
  }

  const record = JSON.parse(raw) as ClipRecord;

  // 客户端已有相同内容，返回 304
  if (lastHash && lastHash === record.hash) {
    return c.body(null, 304);
  }

  return c.json(record);
});

// PUT /register - 设备注册在线状态，TTL 15 分钟
app.put("/register", async (c: AppContext) => {
  let body: { device_id: string; device_type: string };
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid JSON" }, 400);
  }

  if (!body.device_id || !body.device_type) {
    return c.json({ error: "missing fields: device_id, device_type" }, 400);
  }

  if (body.device_type !== "android" && body.device_type !== "mac") {
    return c.json({ error: "device_type must be 'android' or 'mac'" }, 400);
  }

  const record: PeerRecord = {
    device_id: body.device_id,
    device_type: body.device_type,
  };
  // TTL 15 分钟（900 秒），过期后自动删除
  await c.env.KV.put(`online:${body.device_id}`, JSON.stringify(record), {
    expirationTtl: 900,
  });

  return c.json({ status: "ok" });
});

// GET /peers - 列出在线设备
app.get("/peers", async (c: AppContext) => {
  const list = await c.env.KV.list({ prefix: "online:" });
  const peers: PeerRecord[] = [];

  for (const key of list.keys) {
    const raw = await c.env.KV.get(key.name);
    if (raw) {
      peers.push(JSON.parse(raw) as PeerRecord);
    }
  }

  return c.json({ peers });
});

export default app;
