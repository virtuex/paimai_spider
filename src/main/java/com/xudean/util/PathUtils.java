package com.xudean.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;


public class PathUtils {

    public static String getTempatePath() throws IOException {
        return "template/template.xlsx";
    }

    public static String getPingAnTempatePath() throws IOException {
        return "files/template/template_pingan.xls";
    }
}
