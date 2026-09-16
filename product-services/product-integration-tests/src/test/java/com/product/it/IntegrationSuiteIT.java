package com.product.it;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * 集成套件聚合入口（failsafe 默认只选中本类）。
 *
 * <p>类间顺序即数据依赖顺序，全部共享同一 IT 栈与同一组服务库：</p>
 * <ol>
 *   <li>{@link ConcurrentSchedulingIT}——先跑并发/标记场景（此时尚无其他套件的 READY
 *       负载，资源事件触发的自动重排不受干扰）；</li>
 *   <li>{@link CrossServiceStateLinkageIT}——异常级联与资源链（其 DOWN/AVAILABLE 自动重排
 *       只会覆盖自己段内的 READY 任务：并发套件任务已终态、跨班次套件尚未播种）；</li>
 *   <li>{@link CrossShiftSchedulingIT}——最后跑排程窗口场景（运行期间无任何资源事件触发
 *       外来全量重排，窗口数学不被干扰）；</li>
 *   <li>{@link LowestCostCompositeCostIT}——LOWEST_COST 综合成本场景（无资源事件、
 *       自带候选池封闭门控，运行期间不受/不影响其他套件数据面）。</li>
 * </ol>
 *
 * <p>单类调试：{@code mvn verify -Dit.test=CrossShiftSchedulingIT}（it.test 覆盖 includes）；
 * 各套件 BeforeAll 均先做双向清理，独立运行亦可重入。</p>
 */
@Suite
@SelectClasses({
        ConcurrentSchedulingIT.class,
        CrossServiceStateLinkageIT.class,
        CrossShiftSchedulingIT.class,
        LowestCostCompositeCostIT.class
})
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
public class IntegrationSuiteIT {
}
