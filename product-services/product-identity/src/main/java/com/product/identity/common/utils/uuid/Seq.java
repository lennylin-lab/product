package com.product.identity.common.utils.uuid;

import com.product.identity.common.utils.DateUtils;
import com.product.identity.common.utils.StringUtils;

import java.util.concurrent.atomic.AtomicInteger;

/// 序列号生成工具类
/// 提供线程安全的序列号生成功能，支持不同业务场景的序列号生成：
/// - 通用序列号：用于一般业务场景
/// - 上传序列号：专门用于文件上传场景
/// 序列号格式：yyMMddHHmmss + 机器标识 + 循环递增序列
/// 例如：241220143045A001
///
/// @author fast
public class Seq
{
    /**
     * 通用序列类型标识
     * 用于标识普通的业务序列号生成场景
     */
    public static final String commSeqType = "COMMON";

    /**
     * 上传序列类型标识
     * 专门用于文件上传场景的序列号生成
     */
    public static final String uploadSeqType = "UPLOAD";

    /**
     * 通用序列计数器
     * 使用AtomicInteger保证线程安全，从1开始计数
     */
    private static AtomicInteger commSeq = new AtomicInteger(1);

    /**
     * 上传序列计数器
     * 文件上传专用计数器，与通用序列分离计数
     */
    private static AtomicInteger uploadSeq = new AtomicInteger(1);

    /**
     * 机器标识码
     * 用于在分布式环境中区分不同机器生成的序列号
     * 当前设置为"A"，可根据实际部署环境调整
     */
    private static final String machineCode = "A";

    /**
     * 获取通用序列号
     * 使用默认的通用序列类型生成16位序列号
     *
     * @return 通用序列号，格式：yyMMddHHmmss + 机器标识 + 3位序列
     */
    public static String getId()
    {
        return getId(commSeqType);
    }

    /**
     * 根据类型生成序列号
     * 支持通用和上传两种序列类型，每种类型使用独立的计数器
     *
     * @param type 序列类型，使用commSeqType或uploadSeqType常量
     * @return 16位序列号，格式：yyMMddHHmmss + 机器标识 + 3位序列
     */
    public static String getId(String type)
    {
        // 根据类型选择对应的计数器
        AtomicInteger atomicInt = commSeq;
        if (uploadSeqType.equals(type))
        {
            atomicInt = uploadSeq;
        }
        return getId(atomicInt, 3);
    }

    /**
     * 生成自定义长度的序列号
     * 生成格式：时间戳 + 机器标识 + 指定长度的循环递增序列
     *
     * @param atomicInt 原子计数器，保证线程安全
     * @param length 序列部分的有效数字长度
     * @return 序列号，总长度为14(时间) + 1(机器) + length(序列)
     */
    public static String getId(AtomicInteger atomicInt, int length)
    {
        // 构建序列号：时间戳 + 机器标识 + 循环序列
        String result = DateUtils.dateTimeNow();
        result += machineCode;
        result += getSeq(atomicInt, length);
        return result;
    }

    /**
     * 生成循环递增的序列字符串
     * 生成范围：[1, 10^length)，达到最大值后重置为1
     * 使用synchronized保证方法级别的线程安全
     *
     * @param atomicInt 原子计数器
     * @param length 序列的数字位数，决定循环范围
     * @return 左补零的定长序列字符串，如length=3时返回"001"、"999"等
     */
    private synchronized static String getSeq(AtomicInteger atomicInt, int length)
    {
        // 先获取当前值，再递增（原子操作保证线程安全）
        int value = atomicInt.getAndIncrement();

        // 计算最大序列值：10的length次方
        // 如果递增后的值达到最大值，则重置为1
        int maxSeq = (int) Math.pow(10, length);
        if (atomicInt.get() >= maxSeq)
        {
            atomicInt.set(1);
        }

        // 将数值转换为指定位数的字符串，不足位数左补零
        // 例如：value=1, length=3 -> "001"
        return StringUtils.padl(value, length);
    }
}
