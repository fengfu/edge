/**
 * InterfaceLoader.java
 */
package com.ofpay.edge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ofpay.edge.bean.ServiceBean;

/**
 * <p>
 * 接口加载类
 * </p>
 * @author Angus
 * @date 2013-3-11
 * @version 1.0
 * @since 1.0
 */
public class InterfaceLoader {

    /**
     * 注册中心上的Provider缓存
     * key: group/com.xxx.xxxProvider:version
     * value: dubbo服务Map<Long, URL>
     */
    public static ConcurrentMap<String, Map<Long, URL>> REGISTRY_PROVIDER_CACHE = new ConcurrentHashMap<String, Map<Long, URL>>();

    private static RegistryConfig registry = null;

    private static ApplicationConfig application = null;

    private static Logger logger = LoggerFactory.getLogger(InterfaceLoader.class);

    public static void init(RegistryConfig registry, ApplicationConfig application) {
        InterfaceLoader.registry = registry;
        InterfaceLoader.application = application;
    }

    public static Object getServiceBean(String serviceKey, String serviceUrl) {
        URL url = getRandomRegisterCacheURL(serviceKey);

        if (url == null) {
            return null;
        }

        // 引用远程服务
        ReferenceConfig<Object> reference = new ReferenceConfig<Object>(); // 此实例很重，封装了与注册中心的连接以及与提供者的连接，请自行缓存，否则可能造成内存和连接泄漏
        reference.setApplication(application);
        reference.setRegistry(registry); // 多个注册中心可以用setRegistries()
        reference.setCheck(false);
        reference.setInterface(url.getPath());
        if (StringUtils.hasText(serviceUrl)) {
            reference.setUrl(serviceUrl); // 指定调用服务
        }
        reference.setVersion(url.getParameter("version"));
        // 和本地bean一样使用xxxService
        return reference.get(); // 注意：此代理对象内部封装了所有通讯细节，对象较重，请缓存复用
    }

    /**
     * 获取Bean
     * @param serviceKey 格式为：group/packege.beanName:version;
     * @return
     */
    public static Object getServiceBean(String serviceKey) {
        return getServiceBean(serviceKey, null);
    }

    public static Method getServiceMethod(Object serviceBean, String methodName) {
        Method serviceMethod = null;
        Class<?> clazz = serviceBean.getClass();
        Method[] methods = clazz.getDeclaredMethods();

        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                serviceMethod = method;
            }
        }
        return serviceMethod;
    }

    /**
     * 获取参数描述
     * @param serviceKey 格式为：group/packege.beanName:version;
     * @param inputMethodName 方法名
     * @return
     */
    public static String getParamDesc(String serviceKey, String inputMethodName) {
        URL url = getRandomRegisterCacheURL(serviceKey);

        if (url == null) {
            return null;
        }

        String paramDesc = "";

        String clazzName = url.getPath();

        try {
            Class<?> clazz = Class.forName(clazzName);
            logger.info("got {} in registry !!!!", clazzName);

            Method[] methods = clazz.getDeclaredMethods();

            for (Method method : methods) {
                String methodName = method.getName();
                if (methodName.equals(inputMethodName))
                    try {
                        Class<?>[] types = method.getParameterTypes();

                        if (types != null) {
                            Object[] objs = new Object[types.length];

                            for (int i = 0; i < objs.length; i++) {
                                try {
                                    Class<?> type = types[i];
                                    if (InterfaceLoader.isWrapClass(type) || type.isEnum() || type.isInterface()) {
                                        objs[i] = null;
                                    } else if (type.isArray()) {
                                        objs[i] = new Object[0];
                                    } else {
                                        objs[i] = type.newInstance();
                                    }
                                } catch (Exception e) {
                                    objs[i] = null;
                                    logger.error("加载{}方法的参数描述出错", new Object[] { inputMethodName, e });
                                }
                            }
                            paramDesc = JSON.toJSONString(objs, SerializerFeature.QuoteFieldNames,
                                    SerializerFeature.UseSingleQuotes, SerializerFeature.WriteMapNullValue,
                                    SerializerFeature.WriteNullStringAsEmpty, SerializerFeature.WriteNullListAsEmpty,
                                    SerializerFeature.SortField);
                        }
                    } catch (Exception e2) {
                        logger.warn("can not found method {} in class", methodName);
                    }
            }

        } catch (Exception e) {
            logger.warn("can not found bean {} in context", clazzName);
        }

        return paramDesc;

    }

    public static String[] getServiceUrl(String serviceKey) {
        Map<Long, URL> map = InterfaceLoader.REGISTRY_PROVIDER_CACHE.get(serviceKey);

        String[] serviceUrls = null;
        if (map != null) {
            Set<Long> urlSet = map.keySet();
            serviceUrls = new String[urlSet.size()];
            int i = 0;
            for (Long id : urlSet) {
                URL dubboURL = map.get(id);
                serviceUrls[i] = dubboURL.getProtocol() + "://" + dubboURL.getHost() + ":" + dubboURL.getPort();
                i++;
            }
        }
        return serviceUrls;
    }

    public static URL getRandomRegisterCacheURL(String serviceKey) {
        Map<Long, URL> map = InterfaceLoader.REGISTRY_PROVIDER_CACHE.get(serviceKey);
        if (map != null) {
            Set<Long> urlSet = map.keySet();
            for (Long id : urlSet) {
                return map.get(id);
            }
        }
        return null;
    }

    /**
     * 所有的Bean的Map；
     * key为packege.beanName@version@group;
     * value为bean实例
     */
    public static Map<String, ServiceBean> allBeanMap = new TreeMap<String, ServiceBean>();

    /**
     * 所有的methdo的Map；
     * key为beanName.methodName;
     * value为Method
     */
    public static Map<String, Method> allMethodMap = new TreeMap<String, Method>();

    /**
     * 接口入参描述的map
     * key为beanName.methodName;
     * value为接口入参的Json描述
     */
    public static Map<String, String> allMethodMapParamDesc = new TreeMap<String, String>();

    public static boolean isWrapClass(Class<?> clz) {
        try {
            return clz.isPrimitive() || clz.getName().startsWith("java.lang");
        } catch (Exception e) {
            return false;
        }
    }
}
