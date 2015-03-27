package com.ctrip.zeus.service.model;

import com.ctrip.zeus.model.entity.Slb;
import org.unidal.dal.jdbc.DalException;

import java.util.List;

/**
 * @author:xingchaowang
 * @date: 3/7/2015.
 */
public interface SlbQuery {
    Slb get(String slbName) throws DalException;

    Slb getById(long id) throws DalException;

    Slb getBySlbServer(String slbServerIp) throws DalException;

    List<Slb> getAll() throws DalException;

    List<Slb> getByAppServer(String appServerIp) throws DalException;

    List<Slb> getByAppName(String appName) throws DalException;

    List<Slb> getByAppServerAndAppName(String appServerIp, String appName) throws DalException;

    List<String> getAppServersBySlb(String slbName) throws DalException;
}