-- KEYS[1] = 使用者的連線數計數器 key
-- ARGV[1] = TTL 秒數
local count = redis.call('decr', KEYS[1])
if count <= 0 then
    redis.call('del', KEYS[1])
else
    redis.call('expire', KEYS[1], ARGV[1])
end
return count
