package com.xudean.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;


public class PathUtils {


    public static   String getTempatePath() throws IOException {
        Resource resource = new ClassPathResource("template/template.xlsx");
        return resource.getFile().getAbsolutePath();
    }
}