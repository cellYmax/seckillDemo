package com.seckill.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.seckill.dto.Result;
import com.seckill.entity.VoucherOrder;
import com.seckill.mapper.VoucherOrderMapper;
import com.seckill.service.ISeckillVoucherService;
import com.seckill.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seckill.utils.RedisIdWorker;
import com.seckill.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true) {
                try {
                    //1.????????????????????????
//                    VoucherOrder voucherOrder = orderTasks.take();
                    //XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.??????????????????????????????
                    if (list == null || list.isEmpty()) {
                        //2.1 ???????????????????????????????????????????????????????????????
                        continue;
                    }
                    //??????????????????????????????
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //3.?????????????????????????????????
                    handleVoucherOrder(voucherOrder);
                    //4.ACK??????
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("??????????????????", e);
                    hanlePendingList();
                }

            }
        }

        private void hanlePendingList() {
            while(true) {
                try {
                    //1.????????????????????????
//                    VoucherOrder voucherOrder = orderTasks.take();
                    //XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.??????????????????????????????
                    if (list == null || list.isEmpty()) {
                        //2.1 ???????????????????????????pending-list????????????????????????????????????
                        break;
                    }
                    //??????????????????????????????
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //3.?????????????????????????????????
                    handleVoucherOrder(voucherOrder);
                    //4.ACK??????
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("??????pending-list????????????", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //???????????????
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //?????????
        boolean isLock = lock.tryLock();
        //??????????????????
        if (!isLock) {
            //???????????????????????????????????????
            log.error("?????????????????????");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

/**   @Override
//    public Result seckillVoucher(Long voucherId) {
//        //????????????id
//        Long userId = UserHolder.getUser().getId();
//        //????????????id
//        long orderId = redisIdWorker.nextId("orderId");
//        //1.??????lua??????
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString(), String.valueOf(orderId)
//        );
//        int r = result.intValue();
//        //2.???????????????0
//        if (r != 0) {
//            return Result.fail(r == 1 ? "????????????" : "??????????????????");
//        }
//
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(0);
    }
 **/

    @Override
    public Result seckillVoucher(Long voucherId) {
        //????????????id
        Long userId = UserHolder.getUser().getId();
        //????????????id
        long orderId = redisIdWorker.nextId("orderId");
        //1.??????lua??????
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        //2.???????????????0
        if (r != 0) {
            return Result.fail(r == 1 ? "????????????" : "??????????????????");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(0);
    }

    /**
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.???????????????
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //2.??????????????????????????????
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("??????????????????!");
//        }
//        //3.??????????????????????????????
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("??????????????????!");
//        }
//        //4.????????????????????????
//        if (voucher.getStock() < 1) {
//            return Result.fail("???????????????");
//        }
//
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        //???????????????
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //?????????
//        boolean isLock = lock.tryLock();
//        //??????????????????
//        if (!isLock) {
//            //???????????????????????????????????????
//            return Result.fail("?????????????????????");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }**/

    @NotNull
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //????????????
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
             log.error("???????????????????????????");
        }
        //5.????????????
        boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                    .update();
        if (!success) {
            //????????????
            log.error("???????????????");
        }
        //6.????????????
        save(voucherOrder);
    }
}
