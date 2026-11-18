local count = redis.call('GET', KEYS[1])

if not count then
    redis.call('SET', KEYS[1], 1, 'PX', ARGV[2])
    return 0
end

if tonumber(count) < tonumber(ARGV[1]) then
    redis.call('INCR', KEYS[1])
    return 0
end

local ttl = redis.call('PTTL', KEYS[1])

if ttl < 0 then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    ttl = tonumber(ARGV[2])
end

return math.max(ttl, 1)


-- KEYS[1]	Counter key for one endpoint
-- ARGV[1]	Maximum permitted sends per window
-- ARGV[2]	Window duration in milliseconds