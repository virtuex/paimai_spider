package com.xudean.spider.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.xudean.SpiderApplication;
import com.xudean.config.SpiderControlConfig;
import com.xudean.db.DbOperator;
import com.xudean.handler.CustomCellWriteHandler;
import com.xudean.pojo.HouseItem;
import com.xudean.spider.ISpider;
import com.xudean.util.DateUtil;
import com.xudean.util.JSONUtil;
import com.xudean.util.PathUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author xuda
 */
public class AliFangChanSpierImpl implements ISpider {
    private static final Logger log = LoggerFactory.getLogger(AliFangChanSpierImpl.class);
    /**
     * 东莞房产初始URL
     */
    private static final String DONG_GUAN_FANGCHAN_URL_PREFIX = "https://sf.taobao.com/item_list.htm?spm=a213w.7398504.filter.85.17746888uuobpt&category=50025969&auction_source=0&city=%B6%AB%DD%B8&sorder=1&st_param=-1&auction_start_seg=-1&page=";
    private static final String DONG_GUAN_FANGCHAN_URL_INDEX = "https://sf.taobao.com/item_list.htm?spm=a213w.7398504.filter.85.17746888uuobpt&category=50025969&auction_source=0&city=%B6%AB%DD%B8&sorder=1&st_param=-1&auction_start_seg=-1&page=1";
    private List<HouseItem> allHouse;
    private ExecutorService cachedThreadPool;
    //用来保存任务开始的日期，该日期同样作为存储结构的最上级目录
    private String datePath;



    /**
     * 爬取的条数
     */
    private AtomicInteger index = new AtomicInteger(1);

    public AliFangChanSpierImpl(int threadNums,String datePath) {
        this.allHouse = new CopyOnWriteArrayList<>();
        this.cachedThreadPool = Executors.newFixedThreadPool(threadNums);
        this.datePath = datePath;
    }


    @Override
    public void startSpider() throws IOException {
        Integer totalPage = getStartPageAndEndPage();
        log.info("获取到总页数:{}", totalPage);
        for (int i = 1; i <= totalPage; i++) {
            if(i==2 && SpiderControlConfig.isDebug==true){
                break;
            }
            getEveryPageHouseItem(i);
        }
        while (true) {
            if (((ThreadPoolExecutor) cachedThreadPool).getActiveCount()==0) {
                log.info("淘宝-所有线程执行完毕，开始保存文件");
                break;
            }
            try {
                //等待三秒再检查
                Thread.sleep(3000);
            } catch (Exception e) {

            }
        }
        EasyExcel.write("files/"+this.datePath+"/淘宝-东莞住宅用房拍卖-司法拍卖-阿里拍卖_拍卖房产汽车车牌土地海关罚没等.xlsx", HouseItem.class).registerWriteHandler(new CustomCellWriteHandler()).withTemplate(PathUtils.getTempatePath()).sheet().doWrite(allHouse);
//        //保存Excel
//        EasyExcel.write("files/"+this.datePath+"/淘宝-东莞住宅用房拍卖-司法拍卖-阿里拍卖_拍卖房产汽车车牌土地海关罚没等.xlsx", HouseItem.class)
//                .sheet().doWrite(allHouse);
        log.info("保存文件成功！");
        cachedThreadPool.shutdown();

    }




    private void getEveryPageHouseItem(int page) throws IOException {
        log.info("=============当前爬取页数:第{}页==================", page);
        //分别获取每页的房产信息
        String nextUrl = getNextUrl(page);
        Document document = Jsoup.connect(nextUrl).timeout(5000).get();
        String oriData = document.getElementById("sf-item-list-data").toString();
        //接下来对数据进行处理
        //1.找到“>”标签的位置
        int start = oriData.indexOf(">");
        //2.找到"</"标签的位置
        int end = oriData.indexOf("</");
        //截取出值
        String data = oriData.substring(start + 1, end);
        Map<String, Object> itemsListMap = JSONUtil.toMap(data);
        ArrayList<Map<String, Object>> items = (ArrayList<Map<String, Object>>) itemsListMap.get("data");
        //循环获取每页的商品详情
        for (Map<String, Object> item : items) {
            Object itemUrl = item.get("itemUrl");
            //
            Document detailItem = Jsoup.connect("https:" + itemUrl).timeout(5000).get();
            //获取每页详情的时候可以交给线程池区爬取
            cachedThreadPool.execute(new Runnable() {
                @SneakyThrows
                @Override
                public void run() {
                    HouseItem houseItem = null;
                    try {
                        houseItem = getItemDetailInfo(detailItem);
                    } catch (IOException e) {
                        log.error("获取详情失败:{}",e.getMessage(),e);
                    }
                    allHouse.add(houseItem);
                    log.info("----------已爬取{}条---------------", index.incrementAndGet());
                }
            });


        }

    }

    /**
     * 解析具體的商品詳情
     */
    private HouseItem getItemDetailInfo(Document document) throws IOException {
        HouseItem houseItem = new HouseItem();
        houseItem.setUrl(document.baseUri());
        //地址
        Elements h1 = document.getElementsByTag("h1");
        houseItem.setHouseAddress(excludeTag(h1.text().replaceAll(" +", "")));
        log.info("开始获取【{}】的详情", houseItem.getHouseAddress());
        //起拍价
        Elements select = document.select("span[class=pm-current-price J_Price]");
        //获取单位
        Elements unit = document.select("em[class=rmb-unit]");
        String startPay = select.text();
        houseItem.setOpeningPrice(startPay);

        //开始时间
        Elements startDateEle = document.select("li[class=J_PItem]");
        String endTimestamp = startDateEle.attr("data-end");
        Date date = new Date();
        if (StringUtils.isEmpty(endTimestamp)) {
            houseItem.setStartDate("为获取到具体时间,请到详情页面查看");
        } else {
            date = new Date(Long.valueOf(endTimestamp));
            String endDataFormat = DateUtil.formatDate(date);
            houseItem.setStartDate(endDataFormat);
        }
//        //处置单位
//        String court = document.select("span[class=unit-txt unit-name item-announcement]").text();
//        houseItem.setCourt(court);

        //户型

        //总面积
        String dataDetail = document.getElementById("J_desc").attr("data-from");
        Document dataDetailDocument = Jsoup.connect("https:" + dataDetail).timeout(5000).get();
        Elements detailTr = dataDetailDocument.getElementsByTag("tr");
        if (detailTr.size() == 0) {
            String text = dataDetailDocument.text();
            String no = StrUtil.subBetween(text, "【", "】");
//            houseItem.setCertificateNo(no);
            String area = StrUtil.subBetween(text, "建筑面积：", "平方米");
            if (StrUtil.isEmpty(area)) {
                area = StrUtil.subBetween(text, "建筑面积：", "㎡");
            }
            if (StrUtil.isNotEmpty(area)) {
                houseItem.setAreaSize(area + "平方米");
            }
        } else {
            for (Element element : detailTr) {
                if (element.text().contains("平方米") || element.text().contains("建筑总面积") || element.text().contains("㎡")) {
                    String desc = element.text().replace("标的物介绍", "");
                    houseItem.setAreaSize(desc);
                }
//                if (element.text().contains("权证情况")) {
//                    String no = element.text().replace("产权情况", "");
//                    houseItem.setCertificateNo(no);
//                }
            }
        }
//        if()

        //看样时间
        Elements elementsByClass = document.getElementsByClass("reserve-samples");
        if(elementsByClass.size()==0&&elementsByClass.text().contains("免费预约看样")){
            houseItem.setSeeDemoTime("需要预约");
        }

        //评估价
        Element paypriceEle = document.getElementById("J_HoverShow");
        Elements td = paypriceEle.getElementsByTag("td");
        for (Element element : td) {
            String replace = element.text().replace(" ", "");
            if (replace.contains("评估价")) {
                String pinggu = replace.replaceAll("评估价", "").replace(":¥", "");
                houseItem.setAppraisalPrice(pinggu);
            }
            if (replace.contains("保证金")) {
                String baozheng = replace.replaceAll("保证金", "").replace(":¥", "");
                houseItem.setEnsurePay(baozheng);
            }

            if (replace.contains("加价幅度")) {
                String priceStep = replace.replaceAll("加价幅度", "").replace(":¥", "");
                houseItem.setPriceStep(priceStep);
            }

        }
        if("0".equals(houseItem.getAppraisalPrice() )||StringUtils.isEmpty(houseItem.getAppraisalPrice())){
            //如果评估价为null，就用起拍价
            houseItem.setAppraisalPrice(houseItem.getOpeningPrice());
        }
        //有照片
        houseItem.setHasPhoto("有");
        //保存附件
        saveAttachFile(document, houseItem);
        //保存图片
        saveImages(dataDetailDocument, houseItem);
        log.info("获取到的数据为{}", JSONUtil.toJSON(houseItem));
        return houseItem;

    }

    private void saveAttachFile(Document document, HouseItem houseItem) throws IOException {
        Element jDownLoadFirst = document.getElementById("J_DownLoadFirst");
        if (jDownLoadFirst == null) {
            jDownLoadFirst = document.getElementById("J_DownLoadSecond");
        }
        String dowloadUrl = jDownLoadFirst.attr("dowload-url");
        String dataFrom = jDownLoadFirst.attr("data-from");
        if (StringUtils.isEmpty(dataFrom)) {
            dataFrom = "//sf.taobao.com/download_attach.do";
        }
        Document attachDocument = Jsoup.connect("https:" + dataFrom).timeout(5000).get();
        Elements body = attachDocument.getElementsByTag("body");
        String attachFilesJsonStr = attachDocument.getElementsByTag("body").text().replace("null(", "").replace(");", "");
        if (StringUtils.isEmpty(attachFilesJsonStr)) {
            return;
        }
        ArrayList<Map<String, Object>> arrayList = JSONUtil.toObject(attachFilesJsonStr, ArrayList.class);
        for (Map<String, Object> fileUrl : arrayList) {
            Object id = fileUrl.get("id");
            String attachUrl = getAttachUrl(dowloadUrl, id.toString());
            String houseFilePath = saveToAttachFile(attachUrl, houseItem.getHouseAddress(), (String) fileUrl.get("title"));
            houseItem.setLocalPath("file://"+houseFilePath.replaceAll("\\\\","/").replaceAll("#","").replaceAll("、",""));
        }

    }

    private String getAttachUrl(String downLoadUrl, String id) {
        return "https:" + downLoadUrl + "?attach_id=" + id;
    }

    /**
     * @return
     * @throws IOException
     */
    private Integer getStartPageAndEndPage() throws IOException {
        Document document = Jsoup.connect(DONG_GUAN_FANGCHAN_URL_INDEX).timeout(5000).get();
        Elements select = document.select("em[class=page-total]");
        Element total = select.first();
        return Integer.valueOf(total.text());
    }

    /**
     * 拼接URL
     *
     * @param nextPageNum
     * @return
     */
    private String getNextUrl(Integer nextPageNum) {
        return DONG_GUAN_FANGCHAN_URL_PREFIX + nextPageNum;
    }

    private String getItemDetailUrl(String subUrl) {
        return "https:" + subUrl;
    }

    private String excludeTag(String ori) {
        return ori.replace("变卖", "").replace("一拍", "").replace("二拍", "");
    }

    // 解析url的元素
    private void saveImages(Document document, HouseItem houseItem) {
        Elements pngs = document.select("img[src$=.jpg]");
        for (Element element : pngs) {
            try {
                if (StringUtils.isEmpty(element.attr("src"))) {
                    continue;
                }
                saveToImages("https:" + element.attr("src"), houseItem.getHouseAddress());
            } catch (IOException e) {
                log.error("保存图片失败，图片地址:{}", houseItem.getHouseAddress());
            }
        }
    }


    // 爬取网络的图片到本地
    public void saveToImages(String destUrl, String dirName) throws IOException {
        URL url = new URL(destUrl);
        HttpURLConnection httpUrl = (HttpURLConnection) url.openConnection();
        httpUrl.connect();
        InputStream inputStream = httpUrl.getInputStream();
        //将图片保存成files/房产地址/xxx.jpg的形式
        String filePath = "files/"+datePath+"/淘宝/" + dirName + "/images/" + UUID.randomUUID().toString() + ".jpg";
        downloadImg(inputStream, filePath);
    }

    /**
     * 保存文件
     *
     * @param destUrl
     * @param dirName
     * @return
     */
// 爬取网络的图片到本地
    public String saveToAttachFile(String destUrl, String dirName, String filename) throws IOException {
        URL url = new URL(destUrl);
        HttpURLConnection httpUrl = (HttpURLConnection) url.openConnection();
        httpUrl.connect();
        String replace = filename.replaceAll("\\\\", "");
        InputStream inputStream = httpUrl.getInputStream();
        //将图片保存成files/房产地址/xxx.jpg的形式
        String filePath = "files/"+datePath+"/淘宝/" + dirName + "/attach/" + replace;
        downloadImg(inputStream, filePath);
        return new File("files/"+datePath+"/淘宝/" + dirName).getAbsolutePath();
    }


    public boolean downloadImg(InputStream inputStream, String path) {
        boolean flag = true;
        File file = new File(path.replace("?", "").replace("!", "").replace("【", "").replace("】", ""));
        File fileParent = file.getParentFile();
        if (!fileParent.exists()) {
            fileParent.mkdirs();//创建路径
        }
        try {
            FileUtils.copyToFile(inputStream, file);
        } catch (Exception e) {
            e.printStackTrace();
            flag = false;
        }
        return flag;
    }

}
