package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter;

import java.io.Serializable;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("list")
public class Movie implements Serializable {
    @XStreamAsAttribute
    public int page;
    @XStreamAsAttribute
    public int pagecount;//总页数
    @XStreamAsAttribute
    public int pagesize;
    @XStreamAsAttribute
    public int recordcount;//总条数
    @XStreamImplicit(itemFieldName = "video")
    public List<Video> videoList;

    @XStreamAlias("video")
    public static class Video implements Serializable {
        @XStreamAlias("last")//时间
        public String last;
        @XStreamAlias("id")//内容id
        public String id;
        @XStreamAlias("tid")//父级id
        public int tid;
        @XStreamAlias("name")//影片名称 <![CDATA[老爸当家]]>
        public String name;
        @XStreamAlias("type")//类型名称
        public String type;
        /*@XStreamAlias("dt")//视频分类 zuidam3u8,zuidall
        public String dt;*/
        @XStreamAlias("pic")//图片
        public String pic;
        @XStreamAlias("lang")//语言
        public String lang;
        @XStreamAlias("area")//地区
        public String area;
        @XStreamAlias("year")//年份
        public int year;
        @XStreamAlias("state")
        public String state;
        @XStreamAlias("note")//描述集数或者影片信息<![CDATA[共40集]]>
        public String note;
        @XStreamAlias("actor")//演员<![CDATA[张国立,蒋欣,高鑫,曹艳艳,王维维,韩丹彤,孟秀,王新]]>
        public String actor;
        @XStreamAlias("director")//导演<![CDATA[陈国星]]>
        public String director;
        @XStreamAlias("dl")
        public UrlBean urlBean;
        @XStreamAlias("des")
        public String des;// <![CDATA[权来]
        /**
         * 别名/副标题/英文名,多个用 {@code |} 分隔(2026-10-08 搜索准确度改造)。
         *
         * <p>为什么需要:列表接口把别名放在 vod_sub / vod_en,而搜索匹配此前**只看 name**,
         * 于是"用别名/英文名/副标题搜得到、却在本客户端被判不匹配"的结果被整批滤掉 ——
         * 用户看到的现象是"明明源站有这部片子,App 里搜不到"。
         *
         * <p>来源:{@code AbsJson.AbsJsonVod.toXmlVideo()} 从 JSON 字段映射;XML 型源无对应字段,
         * 保持为 null,匹配逻辑必须容忍 null。
         */
        @XStreamAlias("alias")
        public String alias;
        public String sourceKey;
        @XStreamAlias("tag")
        public String tag;
        @XStreamAlias("action")
        public String action;

        @XStreamAlias("dl")
        public static class UrlBean implements Serializable {
            @XStreamImplicit(itemFieldName = "dd")
            public List<UrlInfo> infoList;

            @XStreamAlias("dd")
            @XStreamConverter(value = ToAttributedValueConverter.class, strings = {"urls"})
            public static class UrlInfo implements Serializable {
                @XStreamAsAttribute
                public String flag;//zuidam3u8,zuidall(MP4)
                // <![CDATA[第01集$http://video.zuidajiexi.com/20170825/txpkmcnK/index.m3u8#第02集$http://video.zuidajiexi.com/20170825/YOApVCHc/index.m3u8]]
                public String urls;
                public List<InfoBean> beanList;

                public static class InfoBean implements Serializable {
                    public String name;
                    public String url;

                    public InfoBean(String name, String url) {
                        this.name = name;
                        this.url = url;
                    }

                }
            }

        }

    }

}
