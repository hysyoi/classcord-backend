-- KEYS[1] = 使用者的連線數計數器 key
-- KEYS[2] = 在線名單 Set key
-- ARGV[1] = TTL 秒數
-- ARGV[2] = 使用者 ID
local count = redis.call('decr', KEYS[1])
if count <= 0 then
    redis.call('del', KEYS[1])
    redis.call('srem', KEYS[2], ARGV[2])
else
    redis.call('expire', KEYS[1], ARGV[1])
end
return count
