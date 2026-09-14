package com.product.masterdata.common.utils;

import com.product.masterdata.common.constant.Constants;
import com.product.masterdata.common.core.text.Convert;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// Servlet工具类 - HTTP请求响应处理工具
/// 该工具类基于Spring的RequestContextHolder提供便捷的HTTP请求响应操作方法。
/// Spring使用ThreadLocal机制为每个请求线程存储RequestAttributes，使得在任何地方
/// 都能够安全地获取当前请求的HttpServletRequest、HttpServletResponse等信息。
/// 主要功能包括：
/// 1. 请求参数获取和类型转换
/// 2. HTTP请求/响应对象获取
/// 3. Session管理
/// 4. 响应渲染和编码处理
/// 5. Ajax请求识别
///
/// @author fast
/// @version 1.0
/// @since 1.0
public class ServletUtils
{
    /// 获取字符串类型的请求参数
    /// 通过RequestContextHolder获取当前请求，然后获取指定名称的参数值。
    /// 如果参数不存在，返回null。
    ///
    /// @param name 请求参数名称
    /// @return String 参数值，如果不存在返回null
    ///
    /// @example 获取用户名参数
    ///          String username = ServletUtils.getParameter("username");
    public static String getParameter(String name)
    {
        return getRequest().getParameter(name);
    }

    /// 获取字符串类型的请求参数（带默认值）
    /// 获取指定名称的参数值，如果参数不存在或为空，返回指定的默认值。
    /// 这是getParameter方法的便利增强版本，常用于可选参数处理。
    ///
    /// @param name 请求参数名称
    /// @param defaultValue 默认值，当参数不存在时返回
    /// @return String 参数值，如果不存在返回默认值
    ///
    /// @example 获取分页大小参数，默认为10
    ///          Integer pageSize = ServletUtils.getParameter("pageSize", 10);
    public static String getParameter(String name, String defaultValue)
    {
        return Convert.toStr(getRequest().getParameter(name), defaultValue);
    }

    /// 获取整数类型的请求参数
    /// 获取指定名称的参数值并转换为Integer类型。
    /// 如果参数不存在或转换失败，返回null。
    ///
    /// @param name 请求参数名称
    /// @return Integer 参数值，如果不存在或转换失败返回null
    ///
    /// @example 获取用户ID参数
    ///          Integer userId = ServletUtils.getParameterToInt("userId");
    public static Integer getParameterToInt(String name)
    {
        return Convert.toInt(getRequest().getParameter(name));
    }

    /// 获取整数类型的请求参数（带默认值）
    /// 获取指定名称的参数值并转换为Integer类型。
    /// 如果参数不存在或转换失败，返回指定的默认值。
    ///
    /// @param name 请求参数名称
    /// @param defaultValue 默认值，当参数不存在或转换失败时返回
    /// @return Integer 参数值，如果不存在或转换失败返回默认值
    ///
    /// @example 获取页码参数，默认为1
    ///          Integer pageNum = ServletUtils.getParameterToInt("pageNum", 1);
    public static Integer getParameterToInt(String name, Integer defaultValue)
    {
        return Convert.toInt(getRequest().getParameter(name), defaultValue);
    }

    /// 获取布尔类型的请求参数
    /// 获取指定名称的参数值并转换为Boolean类型。
    /// 常用于处理复选框、开关等参数。
    ///
    /// @param name 请求参数名称
    /// @return Boolean 参数值，如果不存在或转换失败返回null
    ///
    /// @example 获取是否启用参数
    ///          Boolean enabled = ServletUtils.getParameterToBool("enabled");
    public static Boolean getParameterToBool(String name)
    {
        return Convert.toBool(getRequest().getParameter(name));
    }

    /// 获取布尔类型的请求参数（带默认值）
    /// 获取指定名称的参数值并转换为Boolean类型。
    /// 如果参数不存在或转换失败，返回指定的默认值。
    ///
    /// @param name 请求参数名称
    /// @param defaultValue 默认值，当参数不存在或转换失败时返回
    /// @return Boolean 参数值，如果不存在或转换失败返回默认值
    ///
    /// @example 获取是否记住登录参数，默认为false
    ///          Boolean rememberMe = ServletUtils.getParameterToBool("rememberMe", false);
    public static Boolean getParameterToBool(String name, Boolean defaultValue)
    {
        return Convert.toBool(getRequest().getParameter(name), defaultValue);
    }

    /**
     * 获得所有请求参数
     *
     * @param request 请求对象{@link ServletRequest}
     * @return Map
     */
    public static Map<String, String[]> getParams(ServletRequest request)
    {
        final Map<String, String[]> map = request.getParameterMap();
        return Collections.unmodifiableMap(map);
    }

    /**
     * 获得所有请求参数
     *
     * @param request 请求对象{@link ServletRequest}
     * @return Map
     */
    public static Map<String, String> getParamMap(ServletRequest request)
    {
        Map<String, String> params = new HashMap<>();
        for (Map.Entry<String, String[]> entry : getParams(request).entrySet())
        {
            params.put(entry.getKey(), StringUtils.join(entry.getValue(), ","));
        }
        return params;
    }

    /// 获取当前线程的HttpServletRequest对象
    /// 通过RequestContextHolder获取当前请求线程的HttpServletRequest。
    /// Spring使用ThreadLocal机制确保每个请求线程都能安全地获取自己的请求对象。
    ///
    /// @return HttpServletRequest 当前HTTP请求对象
    /// @throws IllegalStateException 如果在非请求线程中调用可能抛出异常
    ///
    /// @example 在Service层获取请求对象
    ///          HttpServletRequest request = ServletUtils.getRequest();
    ///          String clientIp = request.getRemoteAddr();
    public static HttpServletRequest getRequest()
    {
        return getRequestAttributes().getRequest();
    }

    /// 获取当前线程的HttpServletResponse对象
    /// 通过RequestContextHolder获取当前请求线程的HttpServletResponse。
    /// 主要用于在非Controller层直接操作响应对象。
    ///
    /// @return HttpServletResponse 当前HTTP响应对象
    /// @throws IllegalStateException 如果在非请求线程中调用可能抛出异常
    ///
    /// @example 在Service层直接写入响应
    ///          HttpServletResponse response = ServletUtils.getResponse();
    ///          response.getWriter().write("success");
    public static HttpServletResponse getResponse()
    {
        return getRequestAttributes().getResponse();
    }

    /// 获取当前请求的HttpSession对象
    /// 获取当前请求关联的Session对象，用于会话管理。
    /// 如果当前请求没有Session，会创建一个新的Session。
    ///
    /// @return HttpSession 当前请求的会话对象
    /// @throws IllegalStateException 如果在非请求线程中调用可能抛出异常
    ///
    /// @example 存储用户信息到Session
    ///          HttpSession session = ServletUtils.getSession();
    ///          session.setAttribute("user", user);
    public static HttpSession getSession()
    {
        return getRequest().getSession();
    }

    /// 获取当前线程的ServletRequestAttributes对象
    /// 这是所有请求响应操作的基础方法。通过RequestContextHolder从ThreadLocal中
    /// 获取当前请求线程的RequestAttributes，然后转换为ServletRequestAttributes。
    /// RequestContextHolder的工作原理：
    /// 1. Spring在请求开始时，将RequestAttributes绑定到当前线程的ThreadLocal
    /// 2. 在请求处理过程中，所有代码都可以通过这个方法访问请求响应对象
    /// 3. 请求结束时，Spring会清理ThreadLocal中的数据
    /// 这样设计的优势：
    /// - 线程安全：每个请求线程都有独立的数据副本
    /// - 便捷访问：不需要方法参数传递，可以在任何地方获取请求对象
    /// - 解耦设计：业务代码不依赖于Servlet API的参数传递
    ///
    /// @return ServletRequestAttributes 当前请求的属性对象
    /// @throws IllegalStateException 如果在非请求线程中调用（如异步线程、定时任务等）
    ///
    /// @example 获取请求属性
    ///          ServletRequestAttributes attributes = ServletUtils.getRequestAttributes();
    ///          HttpServletRequest request = attributes.getRequest();
    ///          HttpServletResponse response = attributes.getResponse();
    public static ServletRequestAttributes getRequestAttributes()
    {
        // 获取当前线程ThreadLocal中的请求属性
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return (ServletRequestAttributes) attributes;
    }

    /// 将字符串直接渲染到HTTP响应客户端
    /// 直接向HTTP响应写入内容，常用于返回JSON数据或错误信息。
    /// 该方法会设置正确的响应头信息，包括状态码、内容类型和字符编码。
    /// 注意：调用此方法后，响应流会被提交，后续不应再向响应写入内容。
    ///
    /// @param response HTTP响应对象
    /// @param string 待渲染的字符串内容（通常为JSON格式）
    ///
    /// @example 直接返回JSON响应
    ///          String json = "{\"code\":200,\"message\":\"success\"}";
    ///          ServletUtils.renderString(response, json);
    public static void renderString(HttpServletResponse response, String string)
    {
        try
        {
            // 设置HTTP状态码为200（成功）
            response.setStatus(200);
            // 设置内容类型为JSON，字符编码为UTF-8
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");
            // 将字符串写入响应输出流
            response.getWriter().print(string);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /// 判断当前请求是否为Ajax异步请求
    /// 通过多种方式判断请求是否为Ajax请求，支持常见的Ajax请求识别方式：
    /// 1. Accept头检查：检查是否包含application/json
    /// 2. X-Requested-With头检查：jQuery等框架常用的Ajax标识
    /// 3. URL后缀检查：检查是否为.json或.xml结尾
    /// 4. 请求参数检查：检查__ajax参数
    /// 常用于处理不同的响应格式，返回JSON或页面跳转等不同处理方式。
    ///
    /// @param request HTTP请求对象
    /// @return boolean 如果是Ajax请求返回true，否则返回false
    ///
    /// @example 根据请求类型返回不同响应
    ///          if (ServletUtils.isAjaxRequest(request)) {
    ///              // 返回JSON格式错误信息
    ///              return AjaxResult.error("操作失败");
    ///          } else {
    ///              // 返回错误页面
    ///          return "error";
    ///      }
    public static boolean isAjaxRequest(HttpServletRequest request)
    {
        // 方式1：检查Accept头是否包含application/json
        String accept = request.getHeader("accept");
        if (accept != null && accept.contains("application/json"))
        {
            return true;
        }

        // 方式2：检查X-Requested-With头（jQuery等Ajax库的标准标识）
        String xRequestedWith = request.getHeader("X-Requested-With");
        if (xRequestedWith != null && xRequestedWith.contains("XMLHttpRequest"))
        {
            return true;
        }

        // 方式3：检查请求URI是否以JSON或XML后缀结尾
        String uri = request.getRequestURI();
        if (StringUtils.inStringIgnoreCase(uri, ".json", ".xml"))
        {
            return true;
        }

        // 方式4：检查__ajax请求参数
        String ajax = request.getParameter("__ajax");
        return StringUtils.inStringIgnoreCase(ajax, "json", "xml");
    }

    /// URL编码工具方法
    /// 使用UTF-8字符集对字符串进行URL编码，确保URL参数的安全传输。
    /// 将特殊字符转换为%XX格式，符合URL编码标准。
    /// 常用于：
    /// - URL参数值编码
    /// - 文件名编码
    /// - 查询字符串编码
    ///
    /// @param str 需要编码的原始字符串
    /// @return String 编码后的URL安全字符串，编码失败返回空字符串
    ///
    /// @example 编码URL参数
    ///          String keyword = "测试&查询";
    ///          String encodedKeyword = ServletUtils.urlEncode(keyword);
    ///          // 结果: "%E6%B5%8B%E8%AF%95%26%E6%9F%A5%E8%AF%A2"
    public static String urlEncode(String str)
    {
        try
        {
            return URLEncoder.encode(str, Constants.UTF8);
        }
        catch (UnsupportedEncodingException e)
        {
            // UTF-8编码是Java标准库必须支持的，理论上不会出现此异常
            return StringUtils.EMPTY;
        }
    }

    /// URL解码工具方法
    /// 使用UTF-8字符集对URL编码的字符串进行解码，还原为原始字符串。
    /// 将%XX格式的编码字符还原为对应的字符。
    /// 常用于：
    /// - 解析URL参数
    /// - 文件名解码
    /// - 查询字符串解码
    ///
    /// @param str 需要解码的URL编码字符串
    /// @return String 解码后的原始字符串，解码失败返回空字符串
    ///
    /// @example 解码URL参数
    ///          String encodedKeyword = "%E6%B5%8B%E8%AF%95%26%E6%9F%A5%E8%AF%A2";
    ///          String keyword = ServletUtils.urlDecode(encodedKeyword);
    ///          // 结果: "测试&查询"
    public static String urlDecode(String str)
    {
        try
        {
            return URLDecoder.decode(str, Constants.UTF8);
        }
        catch (UnsupportedEncodingException e)
        {
            // UTF-8编码是Java标准库必须支持的，理论上不会出现此异常
            return StringUtils.EMPTY;
        }
    }
}
