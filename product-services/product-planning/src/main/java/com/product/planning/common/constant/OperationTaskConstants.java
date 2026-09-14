package com.product.planning.common.constant;

import java.util.Arrays;
import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/31 - 12 - 31 - 19:33
 * @Description: com.product.planning.common.constant
 * @version: 1.0
 */
public class OperationTaskConstants {
    public static final List<String> OP_CODE = Arrays.asList("SETUP", "INJECT", "POST_QC_PUTAWAY");
    public static final List<Long> STD_DURATION_MIM = Arrays.asList(60, 120, 120).stream().map(num -> Long.valueOf(num)).toList();
}
