package com.ant.robot.utils;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;

/**
 * 继承StringUtils类，添加新的Utils
 */
public class EmptyUtils extends StringUtils {

    /**
     * description: 判断实体类对象中的所有属性是否都为空，只用于实体类
     */
    public static boolean fieldsIsEmpty(Object object) {
        if (null == object) {
            return true;
        }
        try {
            for (Field f : object.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.get(object) != null && StringUtils.isNotBlank(f.get(object).toString())) {
                    return false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * 判断基本数据类型是否为空
     */
    public static boolean basicIsEmpty(Object object) {
        if (null == object) {
            return true;
        }
        return StringUtils.isEmpty(trim(object + ""));
    }

    /**
     * 判断集合是否为空, 长度为空也是空
     */
    public static boolean listIsEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * 判断文件是否为空
     */
    public static boolean fileIsEmpty(File file) {
        return file == null || !file.exists() || file.length() == 0;
    }

    /**
     * 去除空的字符串：为空或者空格时，返回null
     */
    public static String removeBasicEmpty(String source) {
        return basicIsEmpty(source) ? null : source.trim();
    }
}
