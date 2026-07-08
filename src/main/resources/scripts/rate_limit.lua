local delta = tonumber(ARGV[2]) or 1
local count = redis.call('incrby', KEYS[1], delta)
-- 如果是第一次計數，或者該 key 意外失去了過期時間 (ttl == -1)，則重新設定過期時間
if count == delta or redis.call('ttl', KEYS[1]) == -1 then
    redis.call('expire', KEYS[1], tonumber(ARGV[1]))
end
return count