package library.benchmark;

import library.connection.ConnectionManager;

import java.sql.*;
import java.util.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * PerformanceEvaluator — Comprehensive JDBC benchmarking suite.
 *
 * Test suites:
 *  1. Insert Strategy   — Individual vs. Batch inserts (1K and 10K records)
 *  2. Query Strategy    — Full-table scan vs. indexed lookup on LOANS
 *  3. Statement Type    — Statement (string concat) vs. PreparedStatement
 *  4. Transaction       — Per-operation commit vs. batched commit (100 ops)
 *
 * Each suite runs 5 iterations (first is warm-up, discarded from averages).
 */
public class PerformanceEvaluator {

    private static final int RUNS        = 5;
    private static final int WARMUP_RUNS = 1;
    private static final int SMALL_BATCH = 1_000;
    private static final int LARGE_BATCH = 10_000;
    private static final int TX_OPS      = 100;

    private final ConnectionManager cm;
    private final List<BenchResult> results = new ArrayList<>();

    public PerformanceEvaluator(ConnectionManager cm) {
        this.cm = cm;
    }

    // ── Public Entry Point ────────────────────────────────────────────────────

    public void runAll() throws SQLException {
        System.out.println("\n" + banner("PERFORMANCE EVALUATION SUITE"));
        System.out.println("Runs per test: " + RUNS + " (first " + WARMUP_RUNS + " warm-up discarded)\n");

        runInsertBenchmarks();
        runQueryBenchmarks();
        runStatementTypeBenchmarks();
        runTransactionGranularityBenchmarks();

        printReport();
        saveCSV();
    }

    // ── Suite 1: Insert Strategy ──────────────────────────────────────────────

    private void runInsertBenchmarks() throws SQLException {
        System.out.println(section("Suite 1: Insert Strategy"));
        for (int count : new int[]{SMALL_BATCH, LARGE_BATCH}) {
            final int c = count;
            results.add(measure("Individual INSERT", count + " records",
                () -> benchIndividualInserts(c)));
            results.add(measure("Batch INSERT (addBatch/executeBatch)", count + " records",
                () -> benchBatchInserts(c)));
        }
    }

    private void benchIndividualInserts(int count) throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(false);
            cleanBench(conn, "IND_" + count);
            String sql = "INSERT INTO BENCHMARK_RECORDS (BatchTag, Payload) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "IND_" + count);
                    ps.setString(2, "r" + i);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            cleanBench(conn, "IND_" + count);
            conn.commit();
        }
    }

    private void benchBatchInserts(int count) throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(false);
            cleanBench(conn, "BAT_" + count);
            String sql = "INSERT INTO BENCHMARK_RECORDS (BatchTag, Payload) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "BAT_" + count);
                    ps.setString(2, "r" + i);
                    ps.addBatch();
                    if ((i + 1) % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            cleanBench(conn, "BAT_" + count);
            conn.commit();
        }
    }

    // ── Suite 2: Query Strategy ───────────────────────────────────────────────

    private void runQueryBenchmarks() throws SQLException {
        ensureLoanData();
        System.out.println(section("Suite 2: Query Strategy"));
        results.add(measure("Full-table scan (LOANS by LoanDate range)", "LOANS table",
            this::benchFullScan));
        results.add(measure("Indexed lookup (LOANS by MemberID)", "LOANS table",
            this::benchIndexedMember));
        results.add(measure("Indexed lookup (LOANS.ReturnDate IS NULL)", "LOANS table",
            this::benchIndexedReturn));
    }

    private void ensureLoanData() throws SQLException {
        try (Connection conn = cm.openNewConnection()) {
            boolean needsData;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM LOANS")) {
                rs.next();
                needsData = rs.getInt(1) < 50;
            }
            if (needsData) {
                conn.setAutoCommit(false);
                String sql = "INSERT INTO LOANS (MemberID,BookID,LoanDate,DueDate,ReturnDate) VALUES (1,2,?,?,?)";
                java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
                java.sql.Date due   = new java.sql.Date(System.currentTimeMillis() + 14L * 86_400_000L);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < 100; i++) {
                        ps.setDate(1, today);
                        ps.setDate(2, due);
                        ps.setDate(3, today);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                conn.setAutoCommit(true);
            }
        }
    }

    private void benchFullScan() throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM LOANS WHERE LoanDate >= '2020-01-01'")) {
                for (int i = 0; i < 200; i++) {
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); }
                }
            }
        }
    }

    private void benchIndexedMember() throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM LOANS WHERE MemberID = ?")) {
                for (int i = 0; i < 200; i++) {
                    ps.setInt(1, (i % 5) + 1);
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); }
                }
            }
        }
    }

    private void benchIndexedReturn() throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM LOANS WHERE ReturnDate IS NULL")) {
                for (int i = 0; i < 200; i++) {
                    try (ResultSet rs = ps.executeQuery()) { rs.next(); }
                }
            }
        }
    }

    // ── Suite 3: Statement Type ───────────────────────────────────────────────

    private void runStatementTypeBenchmarks() throws SQLException {
        System.out.println(section("Suite 3: Statement Type Comparison"));
        results.add(measure("Statement (string concat, 500 SELECTs)", "Members table",
            this::benchRawStatement));
        results.add(measure("PreparedStatement (compiled, 500 SELECTs)", "Members table",
            this::benchPreparedStatement));
    }

    @SuppressWarnings("SqlInjection")
    private void benchRawStatement() throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(true);
            for (int i = 1; i <= 500; i++) {
                String sql = "SELECT * FROM MEMBERS WHERE MemberID = " + ((i % 5) + 1);
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) { /* consume row */ }
                }
            }
        }
    }

    private void benchPreparedStatement() throws Exception {
        try (Connection conn = cm.openNewConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM MEMBERS WHERE MemberID = ?")) {
            conn.setAutoCommit(true);
            for (int i = 1; i <= 500; i++) {
                ps.setInt(1, (i % 5) + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* consume row */ }
                }
            }
        }
    }

    // ── Suite 4: Transaction Granularity ──────────────────────────────────────

    private void runTransactionGranularityBenchmarks() throws SQLException {
        System.out.println(section("Suite 4: Transaction Granularity"));
        results.add(measure("Per-op commit (" + TX_OPS + " inserts)", TX_OPS + " transactions",
            () -> benchPerOpCommit(TX_OPS)));
        results.add(measure("Batched commit (" + TX_OPS + " inserts, 1 tx)", "1 commit",
            () -> benchBatchedCommit(TX_OPS)));
    }

    private void benchPerOpCommit(int count) throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            String sql = "INSERT INTO BENCHMARK_RECORDS (BatchTag,Payload) VALUES (?,?)";
            for (int i = 0; i < count; i++) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, "PER_OP"); ps.setString(2, "v" + i);
                    ps.executeUpdate();
                }
                conn.commit();
            }
            cleanBench(conn, "PER_OP");
            conn.commit();
        }
    }

    private void benchBatchedCommit(int count) throws Exception {
        try (Connection conn = cm.openNewConnection()) {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO BENCHMARK_RECORDS (BatchTag,Payload) VALUES (?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    ps.setString(1, "BATCHED"); ps.setString(2, "v" + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            cleanBench(conn, "BATCHED");
            conn.commit();
        }
    }

    // ── Timing Engine ─────────────────────────────────────────────────────────

    private BenchResult measure(String name, String context, BenchOp op) throws SQLException {
        System.out.printf("  Running: %-60s", name + " [" + context + "] ...");
        double[] ms = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            if (i == WARMUP_RUNS) System.gc();
            long t0 = System.nanoTime();
            try { op.run(); }
            catch (Exception e) {
                System.out.println(" ERROR: " + e.getMessage());
                return new BenchResult(name, context, 0, 0, "ERROR");
            }
            ms[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        double[] timed = Arrays.copyOfRange(ms, WARMUP_RUNS, RUNS);
        double avg = mean(timed);
        double sd  = stdDev(timed, avg);
        System.out.printf(" %.2f ms ± %.2f%n", avg, sd);
        return new BenchResult(name, context, avg, sd, "OK");
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    private void printReport() {
        System.out.println("\n" + banner("PERFORMANCE REPORT"));
        System.out.printf("%-58s %-22s %-14s %-14s %-12s%n",
            "Operation", "Context", "Avg(ms)", "StdDev(ms)", "Ops/sec");
        System.out.println("─".repeat(125));
        for (BenchResult r : results) {
            double ops = r.avgMs > 0 ? 1000.0 / r.avgMs : 0;
            System.out.printf("%-58s %-22s %-14.3f %-14.3f %-12.2f%n",
                r.name, r.context, r.avgMs, r.stdDevMs, ops);
        }
        System.out.println("─".repeat(125));
        System.out.println("\n[NOTE] Ops/sec = full-operation invocations per second.");
        System.out.println("       For insert benchmarks, divide throughput by record count for per-record rate.");
    }

    private void saveCSV() {
        String ts  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fn  = "performance_report_" + ts + ".csv";
        try (PrintWriter pw = new PrintWriter(new FileWriter(fn))) {
            pw.println("Operation,Context,AvgTime_ms,StdDev_ms,OpsPerSec,Status");
            for (BenchResult r : results) {
                double ops = r.avgMs > 0 ? 1000.0 / r.avgMs : 0;
                pw.printf("\"%s\",\"%s\",%.4f,%.4f,%.4f,\"%s\"%n",
                    r.name, r.context, r.avgMs, r.stdDevMs, ops, r.notes);
            }
            System.out.println("\n[BENCH] Report saved → " + fn);
        } catch (IOException e) {
            System.out.println("[BENCH] Could not save CSV: " + e.getMessage());
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    private double mean(double[] a) {
        double s = 0; for (double v : a) s += v; return s / a.length;
    }

    private double stdDev(double[] a, double mean) {
        double s = 0; for (double v : a) s += (v - mean) * (v - mean);
        return Math.sqrt(s / a.length);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void cleanBench(Connection conn, String tag) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM BENCHMARK_RECORDS WHERE BatchTag = ?")) {
            ps.setString(1, tag); ps.executeUpdate();
        }
    }

    private String banner(String t) {
        int w = 70; String line = "═".repeat(w);
        int p = (w - t.length()) / 2;
        return line + "\n" + " ".repeat(Math.max(0, p)) + t + "\n" + line;
    }

    private String section(String t) {
        return "\n── " + t + " " + "─".repeat(Math.max(0, 55 - t.length()));
    }

    // ── Inner Types ───────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface BenchOp { void run() throws Exception; }

    private static class BenchResult {
        final String name, context, notes;
        final double avgMs, stdDevMs;
        BenchResult(String n, String c, double a, double s, String notes) {
            name=n; context=c; avgMs=a; stdDevMs=s; this.notes=notes;
        }
    }
}
