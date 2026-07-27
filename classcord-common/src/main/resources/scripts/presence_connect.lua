-- KEYS[1] = 使用者的連線數計數器 key
-- ARGV[1] = TTL 秒數
local count = redis.call('incr', KEYS[1])
redis.call('expire', KEYS[1], ARGV[1])
return count
