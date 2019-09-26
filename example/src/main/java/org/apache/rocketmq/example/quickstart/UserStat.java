package org.apache.rocketmq.example.quickstart;

import java.io.Serializable;
import java.util.Date;

public class UserStat implements Serializable {

    private static final long serialVersionUID = -2362850973864859866L;

    private String userId;

    private String appId;

    private Date transDate;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Date getTransDate() {
        return transDate;
    }

    public void setTransDate(Date transDate) {
        this.transDate = transDate;
    }
}