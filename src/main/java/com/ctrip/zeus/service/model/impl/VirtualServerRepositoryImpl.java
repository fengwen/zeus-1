package com.ctrip.zeus.service.model.impl;

import com.ctrip.zeus.dal.core.ArchiveVsDao;
import com.ctrip.zeus.dal.core.ArchiveVsEntity;
import com.ctrip.zeus.dal.core.MetaVsArchiveDo;
import com.ctrip.zeus.exceptions.ValidationException;
import com.ctrip.zeus.model.entity.Domain;
import com.ctrip.zeus.model.entity.VirtualServer;
import com.ctrip.zeus.model.transform.DefaultSaxParser;
import com.ctrip.zeus.service.model.ArchiveService;
import com.ctrip.zeus.service.model.ModelMode;
import com.ctrip.zeus.service.model.VirtualServerRepository;
import com.ctrip.zeus.service.model.handler.SlbQuery;
import com.ctrip.zeus.service.model.handler.SlbValidator;
import com.ctrip.zeus.service.model.handler.VirtualServerValidator;
import com.ctrip.zeus.service.model.handler.impl.VirtualServerEntityManager;
import com.ctrip.zeus.service.nginx.CertificateService;
import com.ctrip.zeus.service.model.IdVersion;
import com.ctrip.zeus.service.query.VirtualServerCriteriaQuery;
import org.springframework.stereotype.Component;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import javax.annotation.Resource;
import java.util.*;

/**
 * Created by zhoumy on 2015/7/27.
 */
@Component("virtualServerRepository")
public class VirtualServerRepositoryImpl implements VirtualServerRepository {
    @Resource
    private VirtualServerCriteriaQuery virtualServerCriteriaQuery;
    @Resource
    private VirtualServerEntityManager virtualServerEntityManager;
    @Resource
    private VirtualServerValidator virtualServerModelValidator;
    @Resource
    private SlbValidator slbModelValidator;
    @Resource
    private SlbQuery slbQuery;
    @Resource
    private CertificateService certificateService;
    @Resource
    private ArchiveVsDao archiveVsDao;

    @Override
    public List<VirtualServer> listAll(Long[] vsIds) throws Exception {
        Set<IdVersion> keys = virtualServerCriteriaQuery.queryByIdsAndMode(vsIds, ModelMode.MODEL_MODE_MERGE_OFFLINE);
        return listAll(keys.toArray(new IdVersion[keys.size()]));
    }

    @Override
    public List<VirtualServer> listAll(IdVersion[] keys) throws Exception {
        List<VirtualServer> result = new ArrayList<>();
        Integer[] hashes = new Integer[keys.length];
        String[] values = new String[keys.length];
        for (int i = 0; i < hashes.length; i++) {
            hashes[i] = keys[i].hashCode();
            values[i] = keys[i].toString();
        }
        for (MetaVsArchiveDo d : archiveVsDao.findAllByIdVersion(hashes, values, ArchiveVsEntity.READSET_FULL)) {
            VirtualServer vs = DefaultSaxParser.parseEntity(VirtualServer.class, d.getContent());
            result.add(vs);
        }
        return result;
    }

    @Override
    public VirtualServer getById(Long vsId) throws Exception {
        IdVersion[] key = virtualServerCriteriaQuery.queryByIdAndMode(vsId, ModelMode.MODEL_MODE_MERGE_OFFLINE);
        return getByKey(key[0]);
    }

    @Override
    public VirtualServer getByKey(IdVersion key) throws Exception {
        MetaVsArchiveDo d = archiveVsDao.findByVsAndVersion(key.getId(), key.getVersion(), ArchiveVsEntity.READSET_FULL);
        return d == null ? null : DefaultSaxParser.parseEntity(VirtualServer.class, d.getContent());
    }

    @Override
    public VirtualServer add(Long slbId, VirtualServer virtualServer) throws Exception {
        virtualServer.setSlbId(slbId);
        for (Domain domain : virtualServer.getDomains()) {
            domain.setName(domain.getName().toLowerCase());
        }
        Set<Long> retained = new HashSet<>();
        for (IdVersion idVersion : virtualServerCriteriaQuery.queryBySlbId(slbId)) {
            retained.add(idVersion.getId());
        }
        Set<IdVersion> keys = virtualServerCriteriaQuery.queryByIdsAndMode(retained.toArray(new Long[retained.size()]), ModelMode.MODEL_MODE_REDUNDANT);
        List<VirtualServer> check = listAll(keys.toArray(new IdVersion[keys.size()]));
        check.add(virtualServer);
        virtualServerModelValidator.validateVirtualServers(check);
        virtualServerEntityManager.add(virtualServer);

        if (virtualServer.getSsl().booleanValue()) {
            installCertificate(virtualServer);
        }
        return virtualServer;
    }

    @Override
    public void update(VirtualServer virtualServer) throws Exception {
        if (!virtualServerModelValidator.exists(virtualServer.getId()))
            throw new ValidationException("Virtual server with id " + virtualServer.getId() + " does not exist.");
        for (Domain domain : virtualServer.getDomains()) {
            domain.setName(domain.getName().toLowerCase());
        }
        Set<Long> retained = new HashSet<>();
        for (IdVersion idVersion : virtualServerCriteriaQuery.queryBySlbId(virtualServer.getSlbId())) {
            retained.add(idVersion.getId());
        }
        if (retained.size() == 0) {
            if (!slbModelValidator.exists(virtualServer.getSlbId())) {
                throw new ValidationException("Slb with id " + virtualServer.getSlbId() + " does not exist.");
            }
        }
        Set<IdVersion> keys = virtualServerCriteriaQuery.queryByIdsAndMode(retained.toArray(new Long[retained.size()]), ModelMode.MODEL_MODE_REDUNDANT);
        List<VirtualServer> check = listAll(keys.toArray(new IdVersion[keys.size()]));
        Iterator<VirtualServer> iter = check.iterator();
        while (iter.hasNext()) {
            VirtualServer c = iter.next();
            if (c.getId().equals(virtualServer.getId()) && c.getVersion().equals(virtualServer.getVersion())) {
                iter.remove();
                break;
            }
        }
        check.add(virtualServer);
        virtualServerModelValidator.validateVirtualServers(check);
        virtualServerEntityManager.update(virtualServer);

        if (virtualServer.getSsl().booleanValue()) {
            installCertificate(virtualServer);
        }
    }

    @Override
    public void delete(Long virtualServerId) throws Exception {
        virtualServerModelValidator.removable(getById(virtualServerId));
        virtualServerEntityManager.delete(virtualServerId);
    }

    @Override
    public void installCertificate(VirtualServer virtualServer) throws Exception {
        List<String> ips = slbQuery.getSlbIps(virtualServer.getSlbId());
        List<Domain> vsDomains = virtualServer.getDomains();
        String[] domains = new String[vsDomains.size()];
        for (int i = 0; i < domains.length; i++) {
            domains[i] = vsDomains.get(i).getName();
        }
        Long certId = certificateService.getCertificateOnBoard(domains);
        certificateService.install(virtualServer.getId(), ips, certId);
    }

    @Override
    public void updateStatus(IdVersion[] vses, ModelMode state) throws Exception {
        switch (state) {
            case MODEL_MODE_ONLINE:
                virtualServerEntityManager.updateStatus(vses);
                return;
            default:
                throw new NotImplementedException();
        }
    }

    @Override
    public void updateStatus(IdVersion[] vses) throws Exception {
        updateStatus(vses, ModelMode.MODEL_MODE_ONLINE);
    }

    @Override
    public Set<Long> port(Long[] vsIds) throws Exception {
        return virtualServerEntityManager.port(vsIds);
    }
}
