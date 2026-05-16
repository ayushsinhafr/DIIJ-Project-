package library.connection;

import java.sql.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * ConnectionManager — Responsible for:
 *  1. Initializing the embedded Apache Derby database
 *  2. Managing the connection lifecycle (open/close)
 *  3. Creating and verifying schema (Members, Books, Loans tables + indexes)
 *  4. Populating baseline seed data
 *  5. Graceful shutdown with Derby's shutdown URL
 */
public class ConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectionManager.class.getName());

    // ── Derby embedded URL ────────────────────────────────────────────────────
    public static final String DB_URL      = "jdbc:derby:lab10db;create=true";
    public static final String DRIVER      = "org.apache.derby.jdbc.EmbeddedDriver";
    public static final String SHUTDOWN_URL = "jdbc:derby:lab10db;shutdown=true";

    /** Singleton connection for general application use */
    private Connection connection;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initialise the database: load driver, open connection, create schema if
     * absent, insert seed data.
     */
    public void initialize() throws SQLException {
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new SQLException("Derby JDBC driver not found. Ensure derby.jar is on the classpath.", e);
        }

        connection = DriverManager.getConnection(DB_URL);
        connection.setAutoCommit(true); // DDL runs with auto-commit

        System.out.println("[DB] Connected to embedded Derby: lab10db");

        createSchemaIfAbsent(connection);
        seedDataIfEmpty(connection);

        System.out.println("[DB] Schema verified and seed data loaded.");
    }

    /**
     * Returns the shared application connection.
     * For benchmarking, use {@link #openNewConnection()} instead.
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * Opens a fresh connection for use in benchmarks / transaction scenarios
     * where an independent connection is required.
     * Auto-commit is ON by default; callers that need explicit transactions
     * must call conn.setAutoCommit(false) themselves.
     */
    public Connection openNewConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DB_URL);
        conn.setAutoCommit(true); // safe default — callers opt-in to transactions
        return conn;
    }

    /**
     * Close the shared application connection and shut down Derby cleanly.
     */
    public void shutdown() {
        // 1. Close shared connection
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
        }

        // 2. Shutdown Derby to release file locks
        try {
            DriverManager.getConnection(SHUTDOWN_URL);
        } catch (SQLException e) {
            // Derby always throws SQLState 08006 / XJ015 on clean shutdown — expected
            if ("XJ015".equals(e.getSQLState()) || "08006".equals(e.getSQLState())) {
                System.out.println("[DB] Derby shut down cleanly.");
            } else {
                LOGGER.log(Level.WARNING, "Unexpected shutdown error: " + e.getMessage());
            }
        }
    }

    // ── Schema Creation ───────────────────────────────────────────────────────

    private void createSchemaIfAbsent(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();

        if (!tableExists(meta, "MEMBERS")) {
            executeUpdate(conn, SQL_CREATE_MEMBERS);
            System.out.println("[DB] Created table: MEMBERS");
        }
        if (!tableExists(meta, "BOOKS")) {
            executeUpdate(conn, SQL_CREATE_BOOKS);
            executeUpdate(conn, SQL_CREATE_BOOKS_ISBN_IDX);
            System.out.println("[DB] Created table: BOOKS (+ ISBN index)");
        }
        if (!tableExists(meta, "LOANS")) {
            executeUpdate(conn, SQL_CREATE_LOANS);
            executeUpdate(conn, SQL_CREATE_LOANS_MEMBER_IDX);
            executeUpdate(conn, SQL_CREATE_LOANS_RETURNDATE_IDX);
            System.out.println("[DB] Created table: LOANS (+ MemberID, ReturnDate indexes)");
        }
        if (!tableExists(meta, "BENCHMARK_RECORDS")) {
            executeUpdate(conn, SQL_CREATE_BENCHMARK_RECORDS);
            System.out.println("[DB] Created table: BENCHMARK_RECORDS");
        }
    }

    /** Check if a table exists using DatabaseMetaData (portable). */
    private boolean tableExists(DatabaseMetaData meta, String tableName) throws SQLException {
        try (ResultSet rs = meta.getTables(null, "APP", tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private void executeUpdate(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // ── Seed Data ─────────────────────────────────────────────────────────────

    private void seedDataIfEmpty(Connection conn) throws SQLException {
        // Only seed if members table is empty
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM MEMBERS")) {
            rs.next();
            if (rs.getInt(1) > 0) return; // already seeded
        }

        System.out.println("[DB] Seeding initial data ...");

        // Members
        String insertMember = "INSERT INTO MEMBERS (Name, Email, ActiveLoans) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertMember)) {
            Object[][] members = {
                {"Alice Johnson",  "alice@library.org",  0},
                {"Bob Smith",      "bob@library.org",    1},
                {"Carol Williams", "carol@library.org",  0},
                {"David Brown",    "david@library.org",  2},
                {"Eve Davis",      "eve@library.org",    0},
            };
            for (Object[] m : members) {
                ps.setString(1, (String)  m[0]);
                ps.setString(2, (String)  m[1]);
                ps.setInt   (3, (Integer) m[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Books
        String insertBook = "INSERT INTO BOOKS (ISBN, Title, Author, Available) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertBook)) {
            Object[][] books = {
                {"978-0-13-110362-7", "The C Programming Language",        "Kernighan & Ritchie", 1},
                {"978-0-201-63361-0", "Design Patterns",                   "Gang of Four",        1},
                {"978-0-13-468599-1", "Clean Code",                        "Robert C. Martin",    0},
                {"978-0-321-12521-7", "Domain-Driven Design",              "Eric Evans",          1},
                {"978-0-596-51774-8", "JavaScript: The Good Parts",        "Douglas Crockford",   1},
                {"978-0-13-235088-4", "The Pragmatic Programmer",          "Hunt & Thomas",       1},
                {"978-0-07-352332-3", "Introduction to Algorithms",        "CLRS",                1},
                {"978-0-201-83595-5", "The Mythical Man-Month",            "Fred Brooks",         1},
                {"978-0-13-083573-5", "Refactoring",                       "Martin Fowler",       0},
                {"978-0-13-103805-3", "Structure and Interpretation",      "Abelson & Sussman",   1},
            };
            for (Object[] b : books) {
                ps.setString (1, (String)  b[0]);
                ps.setString (2, (String)  b[1]);
                ps.setString (3, (String)  b[2]);
                ps.setInt    (4, (Integer) b[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Loans — link Bob (MemberID=2) to Clean Code (BookID=3)
        //         and David (MemberID=4) to Refactoring (BookID=9) & C Prog Lang (BookID=1)
        String insertLoan = "INSERT INTO LOANS (MemberID, BookID, LoanDate, DueDate) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertLoan)) {
            // Bob — Clean Code (overdue: 30 days ago)
            ps.setInt  (1, 2); ps.setInt(2, 3);
            ps.setDate (3, Date.valueOf("2026-03-15"));
            ps.setDate (4, Date.valueOf("2026-04-05")); // due in past
            ps.addBatch();
            // David — Refactoring (overdue)
            ps.setInt  (1, 4); ps.setInt(2, 9);
            ps.setDate (3, Date.valueOf("2026-03-20"));
            ps.setDate (4, Date.valueOf("2026-04-10"));
            ps.addBatch();
            // David — The C Programming Language (recent)
            ps.setInt  (1, 4); ps.setInt(2, 1);
            ps.setDate (3, Date.valueOf("2026-05-01"));
            ps.setDate (4, Date.valueOf("2026-05-22"));
            ps.addBatch();
            ps.executeBatch();
        }

        System.out.println("[DB] Seed data inserted: 5 members, 10 books, 3 loans.");
    }

    // ── DDL SQL Strings ───────────────────────────────────────────────────────

    private static final String SQL_CREATE_MEMBERS =
        "CREATE TABLE MEMBERS (" +
        "  MemberID   INTEGER       NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)," +
        "  Name       VARCHAR(100)  NOT NULL," +
        "  Email      VARCHAR(150)  NOT NULL," +
        "  ActiveLoans INTEGER      NOT NULL DEFAULT 0," +
        "  JoinDate   DATE          NOT NULL DEFAULT CURRENT DATE," +
        "  CONSTRAINT PK_MEMBERS PRIMARY KEY (MemberID)," +
        "  CONSTRAINT UQ_MEMBER_EMAIL UNIQUE (Email)," +
        "  CONSTRAINT CHK_ACTIVE_LOANS CHECK (ActiveLoans >= 0 AND ActiveLoans <= 5)" +
        ")";

    private static final String SQL_CREATE_BOOKS =
        "CREATE TABLE BOOKS (" +
        "  BookID    INTEGER      NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)," +
        "  ISBN      VARCHAR(20)  NOT NULL," +
        "  Title     VARCHAR(200) NOT NULL," +
        "  Author    VARCHAR(150) NOT NULL," +
        "  Available INTEGER      NOT NULL DEFAULT 1," +
        "  CONSTRAINT PK_BOOKS   PRIMARY KEY (BookID)," +
        "  CONSTRAINT UQ_ISBN    UNIQUE (ISBN)," +
        "  CONSTRAINT CHK_AVAIL  CHECK (Available IN (0, 1))" +
        ")";

    private static final String SQL_CREATE_LOANS =
        "CREATE TABLE LOANS (" +
        "  LoanID     INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1)," +
        "  MemberID   INTEGER NOT NULL," +
        "  BookID     INTEGER NOT NULL," +
        "  LoanDate   DATE    NOT NULL DEFAULT CURRENT DATE," +
        "  DueDate    DATE    NOT NULL," +
        "  ReturnDate DATE," +
        "  CONSTRAINT PK_LOANS PRIMARY KEY (LoanID)," +
        "  CONSTRAINT FK_LOANS_MEMBER FOREIGN KEY (MemberID) REFERENCES MEMBERS(MemberID)," +
        "  CONSTRAINT FK_LOANS_BOOK   FOREIGN KEY (BookID)   REFERENCES BOOKS(BookID)" +
        ")";

    private static final String SQL_CREATE_BENCHMARK_RECORDS =
        "CREATE TABLE BENCHMARK_RECORDS (" +
        "  RecordID  INTEGER       NOT NULL GENERATED ALWAYS AS IDENTITY," +
        "  BatchTag  VARCHAR(50)   NOT NULL," +
        "  Payload   VARCHAR(200)  NOT NULL," +
        "  InsertTS  TIMESTAMP     NOT NULL DEFAULT CURRENT TIMESTAMP," +
        "  CONSTRAINT PK_BENCH PRIMARY KEY (RecordID)" +
        ")";

    // Indexes
    private static final String SQL_CREATE_BOOKS_ISBN_IDX =
        "CREATE INDEX IDX_BOOKS_ISBN ON BOOKS(ISBN)";

    private static final String SQL_CREATE_LOANS_MEMBER_IDX =
        "CREATE INDEX IDX_LOANS_MEMBERID ON LOANS(MemberID)";

    private static final String SQL_CREATE_LOANS_RETURNDATE_IDX =
        "CREATE INDEX IDX_LOANS_RETURNDATE ON LOANS(ReturnDate)";
}
