package com.miniwatson.service;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GraphService {
    private final TabularSqlService tabular;
    private final VehicleDataProperties data;


    public GraphService(TabularSqlService tabular, VehicleDataProperties data) {
        this.tabular = tabular; this.data = data;
    }

    private void ensure(String table) {
        String path = data.getTables().get(table);
        if (path != null) try { tabular.registerCsvOnce(table, path); } catch (Exception ignore) {}
    }
    private List<List<Object>> rows(String sql, Object... p) {
        try { return tabular.runSelect(sql, p).rows(); } catch (Exception e) { return new ArrayList<>(); }
    }

    /** 부위 어휘 정규화 — 세 소스의 이질적 component를 부품 카탈로그 taxonomy로 통합(온톨로지의 핵심). */
    private static String canon(String col) {
        String u = "upper(cast(" + col + " AS varchar))";
        return "CASE"
                + " WHEN " + u + " LIKE '%AIR BAG%' THEN 'AIR BAGS'"
                + " WHEN " + u + " LIKE '%SEAT BELT%' THEN 'SEAT BELTS'"
                + " WHEN " + u + " LIKE '%BRAKE%' THEN 'BRAKE'"
                + " WHEN " + u + " LIKE '%POWER TRAIN%' OR " + u + " LIKE '%TRANSMISSION%' THEN 'TRANSMISSION'"
                + " WHEN " + u + " LIKE '%ENGINE%' THEN 'ENGINE'"
                + " WHEN " + u + " LIKE '%FUEL%' THEN 'FUEL'"
                + " WHEN " + u + " LIKE '%EXHAUST%' THEN 'EXHAUST'"
                + " WHEN " + u + " LIKE '%TIRE%' THEN 'TIRE'"
                + " WHEN " + u + " LIKE '%WIPER%' OR " + u + " LIKE '%WINDSHIELD%' OR " + u + " LIKE '%VISIBILITY%' THEN 'VISIBILITY'"
                + " WHEN " + u + " LIKE '%ELECTRIC%' THEN 'ELECTRICAL'"
                + " WHEN " + u + " LIKE '%CAMERA%' OR " + u + " LIKE '%REARVIEW%' OR " + u + " LIKE '%BACKOVER%' THEN 'CAMERA'"
                + " ELSE 'OTHER' END";
    }

    /** 차종의 부위별 리스크 맵 — Model→Component 이웃. [정규부위, 리콜수, 불만수] 내림차순. */
    public List<List<Object>> modelComponents(String model) {
        ensure("recalls"); ensure("complaints");
        String m = model == null ? "" : model.trim().toUpperCase();
        return rows(
                "WITH e AS ("
                        + "  SELECT " + canon("component") + " AS canon, 1 rc, 0 cc FROM recalls WHERE upper(model)=? "
                        + "  UNION ALL "
                        + "  SELECT " + canon("components") + " AS canon, 0 rc, 1 cc FROM complaints WHERE upper(model)=? "
                        + ") SELECT canon, SUM(rc) AS recalls, SUM(cc) AS complaints FROM e GROUP BY canon "
                        + "ORDER BY (SUM(rc)+SUM(cc)) DESC", m, m);
    }
    /** 부위 프로파일 — Model→Component→{Recall, Complaint, Part} 순회. */
    public Map<String, Object> componentProfile(String model, String component) {
        ensure("recalls"); ensure("complaints"); ensure("parts");
        String m = model == null ? "" : model.trim().toUpperCase();
        String c = component == null ? "" : component.trim().toUpperCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", m); out.put("component", c);
        out.put("recalls", rows(
                "SELECT nhtsacampaignnumber, reportreceiveddate, component, substr(summary,1,200) "
                        + "FROM recalls WHERE upper(model)=? AND " + canon("component") + "=? "
                        + "ORDER BY reportreceiveddate DESC LIMIT 20", m, c));
        out.put("complaints", rows(
                "SELECT COUNT(*) AS n, "
                        + "SUM(CASE WHEN lower(cast(fire AS varchar)) IN ('true','1','yes') THEN 1 ELSE 0 END) AS fires, "
                        + "COALESCE(SUM(TRY_CAST(numberofinjuries AS INTEGER)),0) AS injuries "
                        + "FROM complaints WHERE upper(model)=? AND " + canon("components") + "=?", m, c));
        out.put("parts", rows(
                "SELECT part, unit_price, labor_hours FROM parts WHERE upper(component)=? "
                        + "ORDER BY TRY_CAST(unit_price AS DOUBLE) DESC", c));
        return out;
    }
}