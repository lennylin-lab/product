package com.product.identity.common.utils.spring;

import com.product.identity.common.utils.StringUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/// Spring工具类 - 方便在非Spring管理环境中获取Bean
/// 该工具类实现了Spring的BeanFactoryPostProcessor和ApplicationContextAware接口，
/// 可以在Spring容器启动时自动获取BeanFactory和ApplicationContext的引用。
/// 提供了便捷的静态方法来获取Spring容器中的Bean实例、检查Bean状态、
/// 获取AOP代理对象以及读取配置文件等功能。
///
/// @author fast
/// @version 1.0
/// @since 1.0
@Component
public final class SpringUtils implements BeanFactoryPostProcessor, ApplicationContextAware
{
    /** Spring Bean工厂，用于获取和管理Bean实例 */
    private static ConfigurableListableBeanFactory beanFactory;

    /** Spring应用上下文环境，用于获取环境配置和属性 */
    private static ApplicationContext applicationContext;

    /**
     * BeanFactory后置处理器方法
     * 在Spring容器启动时自动调用，用于保存BeanFactory的引用
     *
     * @param beanFactory Spring的Bean工厂实例
     * @throws BeansException Bean处理异常
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException
    {
        SpringUtils.beanFactory = beanFactory;
    }

    /**
     * 设置应用上下文方法
     * 实现ApplicationContextAware接口，在Spring容器启动时自动调用，用于保存ApplicationContext的引用
     *
     * @param applicationContext Spring应用上下文实例
     * @throws BeansException Bean处理异常
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException
    {
        SpringUtils.applicationContext = applicationContext;
    }

    /// 根据Bean名称获取Bean实例
    /// 通过Spring容器的BeanFactory根据指定名称获取对应的Bean实例。
    /// 支持泛型，可以自动转换为指定类型。
    ///
    /// @param name Bean在Spring容器中的注册名称
    /// @param <T> 返回的Bean类型
    /// @return T 一个以所给名字注册的bean的实例
    /// @throws BeansException 当Bean不存在或创建失败时抛出异常
    ///
    /// @example 获取名为userService的Bean实例
    ///          UserService userService = SpringUtils.getBean("userService");
    @SuppressWarnings("unchecked")
    public static <T> T getBean(String name) throws BeansException
    {
        return (T) beanFactory.getBean(name);
    }

    /// 根据Bean类型获取Bean实例
    /// 通过Spring容器的BeanFactory根据指定类型获取对应的Bean实例。
    /// 这是获取Bean的首选方式，类型安全且无需知道具体的Bean名称。
    /// 注意：如果容器中存在多个相同类型的Bean，则会抛出异常。
    ///
    /// @param clz Bean的Class对象
    /// @param <T> Bean的类型
    /// @return T 类型为requiredType的对象实例
    /// @throws BeansException 当Bean不存在、类型不匹配或有多个同类型Bean时抛出异常
    ///
    /// @example 获取UserService类型的Bean实例
    ///          UserService userService = SpringUtils.getBean(UserService.class);
    public static <T> T getBean(Class<T> clz) throws BeansException
    {
        T result = (T) beanFactory.getBean(clz);
        return result;
    }

    /// 检查BeanFactory中是否包含指定名称的Bean定义
    /// 用于在获取Bean之前检查该Bean是否已经在Spring容器中定义。
    /// 这是一个安全的检查方法，不会触发Bean的实例化。
    ///
    /// @param name Bean在Spring容器中的注册名称
    /// @return boolean 如果包含指定名称的Bean定义则返回true，否则返回false
    ///
    /// @example 检查userService是否存在
    ///          if (SpringUtils.containsBean("userService")) {
    ///              UserService userService = SpringUtils.getBean("userService");
    ///          }
    public static boolean containsBean(String name)
    {
        return beanFactory.containsBean(name);
    }

    /// 判断指定名称的Bean是否为单例模式
    /// 检查以给定名字注册的Bean定义是一个singleton还是prototype。
    /// 单例模式下，整个Spring容器中只存在一个Bean实例；
    /// 原型模式下，每次请求都会创建一个新的Bean实例。
    ///
    /// @param name Bean在Spring容器中的注册名称
    /// @return boolean 如果是单例模式则返回true，如果是原型模式则返回false
    /// @throws NoSuchBeanDefinitionException 当指定名称的Bean不存在时抛出异常
    ///
    /// @example 检查userService是否为单例
    ///          boolean isSingleton = SpringUtils.isSingleton("userService");
    public static boolean isSingleton(String name) throws NoSuchBeanDefinitionException
    {
        return beanFactory.isSingleton(name);
    }

    /// 获取指定名称Bean的类型信息
    /// 返回在Bean工厂中注册的Bean的实际类型。
    /// 主要用于类型检查和反射操作。
    ///
    /// @param name Bean在Spring容器中的注册名称
    /// @return Class<?> 注册对象的实际类型
    /// @throws NoSuchBeanDefinitionException 当指定名称的Bean不存在时抛出异常
    ///
    /// @example 获取userService的类型
    ///          Class<?> userClass = SpringUtils.getType("userService");
    public static Class<?> getType(String name) throws NoSuchBeanDefinitionException
    {
        return beanFactory.getType(name);
    }

    /// 获取指定Bean的所有别名
    /// 如果给定的Bean名字在Bean定义中有别名，则返回这些别名数组。
    /// 一个Bean可能有多个别名，通过这些别名都可以获取到同一个Bean实例。
    ///
    /// @param name Bean在Spring容器中的主要名称或别名
    /// @return String[] 该Bean的所有别名数组，如果没有别名则返回空数组
    /// @throws NoSuchBeanDefinitionException 当指定名称的Bean不存在时抛出异常
    ///
    /// @example 获取userService的所有别名
    ///          String[] aliases = SpringUtils.getAliases("userService");
    public static String[] getAliases(String name) throws NoSuchBeanDefinitionException
    {
        return beanFactory.getAliases(name);
    }

    /// 获取当前对象的AOP代理对象
    /// 在同一个类中调用带有事务注解的方法时，事务不会生效，
    /// 因为这是直接调用目标对象的方法，而不是通过代理对象。
    /// 使用此方法可以获取当前对象的AOP代理，确保AOP功能（如事务、缓存等）正常工作。
    /// 注意：必须在Spring配置中启用exposeProxy=true才能正常工作。
    ///
    /// @param invoker 需要获取代理的对象实例
    /// @param <T> 对象的类型
    /// @return T 如果当前对象存在AOP代理则返回代理对象，否则返回原对象
    ///
    /// @example 在同一个类中调用事务方法
    ///          public void methodA() {
    ///              SpringUtils.getAopProxy(this).methodB(); // 确保事务生效
    ///          }
    ///          @Transactional
    ///          public void methodB() {
    ///              // 事务处理逻辑
    ///          }
    @SuppressWarnings("unchecked")
    public static <T> T getAopProxy(T invoker)
    {
        Object proxy = AopContext.currentProxy();
        if (((Advised) proxy).getTargetSource().getTargetClass() == invoker.getClass())
        {
            return (T) proxy;
        }
        return invoker;
    }

    /// 获取当前激活的所有环境配置
    /// 从Spring环境配置中获取所有激活的Profile名称。
    /// 在Spring Boot中，可以通过spring.profiles.active参数指定激活的Profile。
    /// 常见的Profile包括：dev（开发环境）、test（测试环境）、prod（生产环境）等。
    ///
    /// @return String[] 当前激活的环境配置数组，如果没有激活任何Profile则返回空数组
    ///
    /// @example 获取所有激活的环境
    ///          String[] activeProfiles = SpringUtils.getActiveProfiles();
    ///          // 返回: ["dev", "mysql"] (如果同时激活了dev和mysql profile)
    public static String[] getActiveProfiles()
    {
        return applicationContext.getEnvironment().getActiveProfiles();
    }

    /// 获取当前激活的环境配置（第一个）
    /// 当有多个环境配置同时激活时，只返回第一个激活的Profile名称。
    /// 这是获取当前运行环境的快捷方法。
    ///
    /// @return String 当前激活的第一个环境配置，如果没有激活任何Profile则返回null
    ///
    /// @example 判断当前是否为开发环境
    ///          String profile = SpringUtils.getActiveProfile();
    ///          if ("dev".equals(profile)) {
    ///              // 开发环境特定逻辑
    ///          }
    public static String getActiveProfile()
    {
        final String[] activeProfiles = getActiveProfiles();
        return StringUtils.isNotEmpty(activeProfiles) ? activeProfiles[0] : null;
    }

    /// 获取配置文件中的必需属性值
    /// 从Spring的配置文件（application.yml、application.properties等）中获取指定key的值。
    /// 如果该属性不存在，将会抛出异常。
    /// 支持从所有配置源获取：系统属性、环境变量、配置文件等。
    ///
    /// @param key 配置文件中的属性键名
    /// @return String 该属性对应的值
    /// @throws IllegalStateException 当指定的属性不存在时抛出异常
    ///
    /// @example 获取数据库配置
    ///          String url = SpringUtils.getRequiredProperty("spring.datasource.url");
    ///          String port = SpringUtils.getRequiredProperty("server.port");
    public static String getRequiredProperty(String key)
    {
        return applicationContext.getEnvironment().getRequiredProperty(key);
    }
}
