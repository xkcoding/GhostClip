use md5::{Digest, Md5};
use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const DEFAULT_TTL: Duration = Duration::from_secs(3);
const DEFAULT_MAX_SIZE: usize = 100;

/// 计算文本的 MD5 哈希
pub fn md5_hash(text: &str) -> String {
    let mut hasher = Md5::new();
    hasher.update(text.as_bytes());
    hex::encode(hasher.finalize())
}

struct HashEntry {
    hash: String,
    created_at: Instant,
}

/// LRU Hash 池，用于剪贴板去重
/// 每个条目有 3 秒 TTL，超时自动清除
pub struct HashPool {
    entries: Mutex<VecDeque<HashEntry>>,
    ttl: Duration,
    max_size: usize,
}

impl HashPool {
    pub fn new() -> Self {
        Self {
            entries: Mutex::new(VecDeque::new()),
            ttl: DEFAULT_TTL,
            max_size: DEFAULT_MAX_SIZE,
        }
    }

    /// 清除过期条目
    fn evict_expired(entries: &mut VecDeque<HashEntry>, ttl: Duration) {
        let now = Instant::now();
        while let Some(front) = entries.front() {
            if now.duration_since(front.created_at) > ttl {
                entries.pop_front();
            } else {
                break;
            }
        }
    }

    /// 检查哈希是否存在于池中（未过期）
    pub fn contains(&self, hash: &str) -> bool {
        let mut entries = self.entries.lock().unwrap();
        Self::evict_expired(&mut entries, self.ttl);
        entries.iter().any(|e| e.hash == hash)
    }

    /// 插入哈希到池中，返回是否为新插入（不重复）
    /// 如果已存在且未过期，返回 false
    pub fn insert(&self, hash: String) -> bool {
        let mut entries = self.entries.lock().unwrap();
        Self::evict_expired(&mut entries, self.ttl);

        // 检查是否已存在
        if entries.iter().any(|e| e.hash == hash) {
            return false;
        }

        // 如果达到容量上限，移除最旧的
        if entries.len() >= self.max_size {
            entries.pop_front();
        }

        entries.push_back(HashEntry {
            hash,
            created_at: Instant::now(),
        });
        true
    }

    /// 检查文本是否为新内容（计算哈希并检查+插入）
    /// 返回 Some(hash) 表示新内容，None 表示重复
    pub fn check_and_insert(&self, text: &str) -> Option<String> {
        let hash = md5_hash(text);
        if self.insert(hash.clone()) {
            Some(hash)
        } else {
            None
        }
    }

    /// 当前池中条目数量（仅未过期的）
    pub fn len(&self) -> usize {
        let mut entries = self.entries.lock().unwrap();
        Self::evict_expired(&mut entries, self.ttl);
        entries.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    #[test]
    fn test_md5_hash() {
        let hash = md5_hash("hello");
        assert_eq!(hash, "5d41402abc4b2a76b9719d911017c592");
    }

    #[test]
    fn test_insert_and_contains() {
        let pool = HashPool::new();
        let hash = md5_hash("test");

        assert!(pool.insert(hash.clone()));
        assert!(pool.contains(&hash));
        // 重复插入返回 false
        assert!(!pool.insert(hash.clone()));
    }

    #[test]
    fn test_check_and_insert() {
        let pool = HashPool::new();

        // 第一次应返回 Some
        assert!(pool.check_and_insert("hello").is_some());
        // 重复应返回 None
        assert!(pool.check_and_insert("hello").is_none());
        // 不同内容应返回 Some
        assert!(pool.check_and_insert("world").is_some());
    }

    #[test]
    fn test_ttl_expiry() {
        let pool = HashPool {
            entries: Mutex::new(VecDeque::new()),
            ttl: Duration::from_millis(100),
            max_size: DEFAULT_MAX_SIZE,
        };

        assert!(pool.check_and_insert("expire_test").is_some());
        assert!(pool.check_and_insert("expire_test").is_none());

        // 等待 TTL 过期
        thread::sleep(Duration::from_millis(150));

        // 过期后应可以重新插入
        assert!(pool.check_and_insert("expire_test").is_some());
    }

    #[test]
    fn test_max_size() {
        let pool = HashPool {
            entries: Mutex::new(VecDeque::new()),
            ttl: Duration::from_secs(60),
            max_size: 3,
        };

        pool.insert(md5_hash("a"));
        pool.insert(md5_hash("b"));
        pool.insert(md5_hash("c"));
        assert_eq!(pool.len(), 3);

        // 超出上限，最旧的应被移除
        pool.insert(md5_hash("d"));
        assert_eq!(pool.len(), 3);
        assert!(!pool.contains(&md5_hash("a")));
        assert!(pool.contains(&md5_hash("d")));
    }
}
