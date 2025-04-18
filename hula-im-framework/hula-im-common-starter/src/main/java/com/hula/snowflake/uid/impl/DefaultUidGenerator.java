/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hula.snowflake.uid.impl;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.hula.snowflake.uid.BitsAllocator;
import com.hula.snowflake.uid.UidGenerator;
import com.hula.snowflake.uid.exception.UidGenerateException;
import com.hula.snowflake.uid.worker.WorkerIdAssigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Represents an implementation of {@link UidGenerator}
 * <p>
 * The unique id has 64bits (long), default allocated as blow:<br>
 * <li>sign: The highest bit is 0
 * <li>delta seconds: The next 28 bits, represents delta seconds since a customer epoch(2016-05-20 00:00:00.000).
 * Supports about 8.7 years until to 2024-11-20 21:24:16
 * <li>worker id: The next 22 bits, represents the worker's id which assigns based on database, max id is about 420W
 * <li>sequence: The next 13 bits, represents a sequence within the same second, max for 8192/s<br><br>
 * <p>
 * The {@link DefaultUidGenerator#parseUid(long)} is a tool method to parse the bits
 *
 * <pre>{@code
 * +------+----------------------+----------------+-----------+
 * | sign |     delta seconds    | worker node id | sequence  |
 * +------+----------------------+----------------+-----------+
 *   1bit          28bits              22bits         13bits
 * }</pre>
 * <p>
 * You can also specified the bits by Spring property setting.
 * <li>timeBits: default as 28
 * <li>workerBits: default as 22
 * <li>seqBits: default as 13
 * <li>epochStr: Epoch date string format 'yyyy-MM-dd'. Default as '2016-05-20'<p>
 *
 * <b>Note that:</b> The total bits must be 64 -1
 *
 * 时间戳位数(timeBits): 41位，这提供了约69年的时间范围，应该足够大多数应用场景。
 * 工作机器ID位数(workerBits): 减少到少于10位。如果你的系统不太可能超过几百个节点，那么8位（支持256个节点）可能就足够了。
 * 序列号位数(seqBits): 剩余的位数可以分配给序列号。这允许我们在每秒内生成更多的ID。
 * 基于64位的限制（其中1位为符号位，永远为0），我们有：
 *
 * 总位数 = 1 + timeBits + workerBits + seqBits = 64
 * 为了确保ID在2^53以内，我们可以按照2^41（时间戳）+ 2^8（工作机器ID）+ 2^14（序列号）进行分配，即41 + 8 + 14 = 63位，留下1位符号位。
 * 示例配置
 * 时间戳位数(timeBits): 41位
 * 工作机器ID位数(workerBits): 9位
 * 序列号位数(seqBits): 13位
 * 这样的配置能够确保在几十年内都不会超出JavaScript的安全整数范围，同时每秒可以生成16384个ID，足以满足大多数应用场景的需要。这种配置平衡了长期使用和高频生成ID的需求，同时避免了超出JavaScript能够精确表示的数值范围的风险。
 *
 * @author yutianbao
 */
public class DefaultUidGenerator implements UidGenerator, InitializingBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultUidGenerator.class);

    long epochSeconds = TimeUnit.MILLISECONDS.toSeconds(1600012800000L);
    /**
     * Stable fields after spring bean initializing
     */
    BitsAllocator bitsAllocator;
    long workerId;
    /**
     * Bits allocate
     */
	/*private int timeBits = 41;
	private int workerBits = 9;
	private int seqBits = 13;*/
	private int timeBits = 41;
	private int workerBits = 13;
	private int seqBits = 9;

    /**
     * Customer epoch, unit as second. For example 2025-02-25
     * 可以改成你的项目开始开始的时间
     */
    private String epochStr = "2025-02-25";
    /**
     * Volatile fields caused by nextId()
     */
    private long sequence = 0L;
    private long lastSecond = -1L;
    /**
     * 当在低频模式下时，序号始终为0，导致生成ID始终为偶数<br>
     * 此属性用于限定一个随机上限，在不同毫秒下生成序号时，给定一个随机数，避免偶数问题。<br>
     * 注意次数必须小于bitsAllocator.getMaxSequence()，{@code 0}表示不使用随机数。<br>
     * 这个上限不包括值本身。
     */
    private long randomSequenceLimit;

    /**
     * Spring property  todo 线程问题需要解决
     */
    private WorkerIdAssigner workerIdAssigner;

    @Override
    public void afterPropertiesSet() throws Exception {
        // 初始化位分配器
        bitsAllocator = new BitsAllocator(timeBits, workerBits, seqBits);

        // 初始化工作程序id
        workerId = workerIdAssigner.assignWorkerId();
        if (workerId > bitsAllocator.getMaxWorkerId()) {
            throw new RuntimeException("Worker id " + workerId + " exceeds the max " + bitsAllocator.getMaxWorkerId());
        }

        // 判断是否超过最大值
        this.randomSequenceLimit = Assert.checkBetween(randomSequenceLimit, 0, bitsAllocator.getMaxSequence());

        LOGGER.info("Initialized bits(1, {}, {}, {}) for workerID:{}", timeBits, workerBits, seqBits, workerId);
    }

    @Override
    public long getUid() throws UidGenerateException {
        try {
            return nextId();
        } catch (Exception e) {
            LOGGER.error("Generate unique id exception. ", e);
            throw new UidGenerateException(e);
        }
    }

    @Override
    public String parseUid(long uid) {
        long totalBits = BitsAllocator.TOTAL_BITS;
        long signBits = bitsAllocator.getSignBits();
        long timestampBits = bitsAllocator.getTimestampBits();
        long workerIdBits = bitsAllocator.getWorkerIdBits();
        long sequenceBits = bitsAllocator.getSequenceBits();

        // parse UID
        final long sequence = (uid << (totalBits - sequenceBits)) >>> (totalBits - sequenceBits);
        final long workerId = (uid << (timestampBits + signBits)) >>> (totalBits - workerIdBits);
        final long deltaSeconds = uid >>> (workerIdBits + sequenceBits);

        Date thatTime = new Date(TimeUnit.SECONDS.toMillis(epochSeconds + deltaSeconds));
        String thatTimeStr = DateUtil.format(thatTime, DatePattern.NORM_DATETIME_PATTERN);

        // format as string //delta seconds    | worker node id | sequence
        return String.format("{\"UID\":\"%d\",\"timestamp\":\"%s\",\"workerId\":\"%d\",\"sequence\":\"%d\"}",
                uid, thatTimeStr, workerId, sequence);
    }

    /**
     * Get UID
     *
     * @return UID
     * @throws UidGenerateException in the case: Clock moved backwards; Exceeds the max timestamp
     */
    protected synchronized long nextId() {
        long currentSecond = getCurrentSecond();

        // Clock moved backwards, refuse to generate uid
        // 时钟回拨问题待解决
        if (currentSecond < lastSecond) {
            long refusedSeconds = lastSecond - currentSecond;
            throw new UidGenerateException("Clock moved backwards. Refusing for %d seconds", refusedSeconds);
        }

        // At the same second, increase sequence
        //同一秒内的，本次发号请求不是本秒的第一次, sequence 加一
        if (currentSecond == lastSecond) {
            sequence = (sequence + 1) & bitsAllocator.getMaxSequence();
            // 同一秒内，超过最大序列(号发完了)，我们等待下一秒生成uid
            if (sequence == 0) {
                currentSecond = getNextSecond(lastSecond);
            }

            // 在不同的秒，序列从零重新启动
        } else {
            // 新的一秒，重新开始发号
            // 低频使用时生成的id总是偶数问题 https://gitee.com/dromara/hutool/issues/I51EJY
            if (randomSequenceLimit > 1) {
                sequence = RandomUtil.randomLong(randomSequenceLimit);
            } else {
                sequence = 0L;
            }
        }

        lastSecond = currentSecond;

        // Allocate bits for UID
        return bitsAllocator.allocate(currentSecond - epochSeconds, workerId, sequence);
    }

    /**
     * Get next millisecond
     */
    private long getNextSecond(long lastTimestamp) {
        long timestamp = getCurrentSecond();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentSecond();
        }

        return timestamp;
    }

    /**
     * Get current second
     */
    private long getCurrentSecond() {
        long currentSecond = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        if (currentSecond - epochSeconds > bitsAllocator.getMaxDeltaSeconds()) {
            throw new UidGenerateException("Timestamp bits is exhausted. Refusing UID generate. Now: " + currentSecond);
        }

        return currentSecond;
    }

    /**
     * Setters for spring property
     */
    public void setWorkerIdAssigner(final WorkerIdAssigner workerIdAssigner) {
        this.workerIdAssigner = workerIdAssigner;
    }

    public void setTimeBits(final int timeBits) {
        if (timeBits > 0) {
            this.timeBits = timeBits;
        }
    }

    public void setWorkerBits(int workerBits) {
        if (workerBits > 0) {
            this.workerBits = workerBits;
        }
    }

    public void setSeqBits(int seqBits) {
        if (seqBits > 0) {
            this.seqBits = seqBits;
        }
    }

    public void setEpochStr(final String epochStr) {
        if (StrUtil.isNotBlank(epochStr)) {
            this.epochStr = epochStr;
            this.epochSeconds = TimeUnit.MILLISECONDS.toSeconds(DateUtil.parse(this.epochStr, DatePattern.NORM_DATE_PATTERN).getTime());
        }
    }

    public long getRandomSequenceLimit() {
        return randomSequenceLimit;
    }
}
