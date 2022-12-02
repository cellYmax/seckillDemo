---- 比较线程标示与锁中的标示是否一致
--if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
--    -- 释放锁 del key
--    return redis.call('del', KEYS[1])
--end
--return 0


local key = KEYS[1];
local threadId = ARGV[1];
local releaseTime = ARGV[2];

if(redis.call("HEXISTS", key, threadId) == 0) then
    return nil;
end

local count = redis.call("HINCRBY", key, threadId, -1);

if(count > 0) then
    redis.call("EXPIRE", key, releaseTime);
    return nil;
else
    redis.call("DEL", key);
    return nil;
end
