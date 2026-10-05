package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("rss")
public class AbsXml implements Serializable {
    public String sourceKey;
    public String searchToken;
    /** 详情代次(V4):发起详情请求时代入,回包原样带回;非详情通道为 null */
    public Integer detailToken;

    @XStreamAlias("list")
    public Movie movie;

    @XStreamAlias("msg")
    public String msg;
}
