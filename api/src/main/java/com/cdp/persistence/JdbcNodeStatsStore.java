package com.cdp.persistence;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cdp.nodestats.NodeStat;
import com.cdp.nodestats.NodeStatsStore;

/** journeys.md §5：以 enrollment_record 與 node_trace 一條 SQL 分組計數；單一語句逾時（預設 5 秒）。 */
@Repository
public class JdbcNodeStatsStore implements NodeStatsStore {

    static final String STATS_SQL =
            "select 'T' k, t.node_id, t.out_port, count(*) c from node_trace t"
                    + " join enrollment_record e on e.enrollment_id = t.enrollment_id"
                    + " where e.journey_id = :j and e.journey_version = :v group by t.node_id, t.out_port"
                    + " union all"
                    + " select 'E', t.node_id, null, count(distinct t.enrollment_id) from node_trace t"
                    + " join enrollment_record e on e.enrollment_id = t.enrollment_id"
                    + " where e.journey_id = :j and e.journey_version = :v group by t.node_id"
                    + " union all"
                    + " select 'C', e.current_node_id, null, count(*) from enrollment_record e"
                    + " where e.journey_id = :j and e.journey_version = :v"
                    + " and e.status in ('PENDING','ACTIVE','WAITING','SUSPENDED') group by e.current_node_id";

    private final JdbcClient jdbc;
    private final JdbcClient timed;

    public JdbcNodeStatsStore(
            JdbcClient jdbc,
            DataSource dataSource,
            @Value("${cdp.node-stats.timeout:PT5S}") Duration timeout) {
        this.jdbc = jdbc;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
        this.timed = JdbcClient.create(template);
    }

    @Override
    public Optional<List<String>> nodeIds(String journeyId, int version) {
        Optional<Boolean> exists =
                jdbc.sql("select true from journey_definition where journey_id = :j and version = :v")
                        .param("j", journeyId)
                        .param("v", version)
                        .query(Boolean.class)
                        .optional();
        if (exists.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                jdbc.sql(
                                "select el.n->>'id' from journey_definition d,"
                                        + " jsonb_array_elements(d.nodes) with ordinality el(n, i)"
                                        + " where d.journey_id = :j and d.version = :v"
                                        + " and el.n->>'type' <> 'StartNode' order by el.i")
                        .param("j", journeyId)
                        .param("v", version)
                        .query(String.class)
                        .list());
    }

    @Override
    public List<NodeStat> stats(String journeyId, int version, List<String> nodeIds) {
        Map<String, Integer> entered = new HashMap<>();
        Map<String, Integer> visits = new HashMap<>();
        Map<String, Integer> current = new HashMap<>();
        Map<String, Map<String, Integer>> ports = new HashMap<>();
        timed.sql(STATS_SQL)
                .param("j", journeyId)
                .param("v", version)
                .query(
                        rs -> {
                            String node = rs.getString("node_id");
                            int c = rs.getInt("c");
                            switch (rs.getString("k")) {
                                case "T" -> {
                                    visits.merge(node, c, Integer::sum);
                                    String port = rs.getString("out_port");
                                    if (port != null) {
                                        ports.computeIfAbsent(node, x -> new LinkedHashMap<>()).put(port, c);
                                    }
                                }
                                case "E" -> entered.put(node, c);
                                default -> current.put(node, c);
                            }
                        });
        return nodeIds.stream()
                .map(
                        id ->
                                new NodeStat(
                                        id,
                                        entered.getOrDefault(id, 0),
                                        visits.getOrDefault(id, 0),
                                        current.getOrDefault(id, 0),
                                        ports.getOrDefault(id, Map.of())))
                .toList();
    }
}
