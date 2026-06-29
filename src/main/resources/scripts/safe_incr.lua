if redis.call('exists', KEYS[1]) == 1 then
    return redis.call('incrby', KEYS[1], tonumber(ARGV[1]))
else
    return nil
end
