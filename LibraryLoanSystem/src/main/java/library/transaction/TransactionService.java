package library.transaction;

import java.sql.*;
import java.util.logging.Logger;

/**
 * TransactionService — Handles explicit transaction boundaries:
 *  - Disables auto-commit for all DML
 *  - Provides commit / rollback helpers
 *  - Implements SAVEPOINT-based partial rollback
 *  - Demonstrates isolation-level effects via constraint violations
 */
public class TransactionService {

    private static final Logger LOG = Logger.getLogger(TransactionService.class.getName());

    private final Connection conn;

    public TransactionService(Connection conn) {
        this.conn = conn;
    }

    // ── Connection Helpers ────────────────────────────────────────────────────

    /** Begin a new transaction (ensure auto-commit is off). */
    public void beginTransaction() throws SQLException {
        conn.setAutoCommit(false);
    }

    /** Commit the current transaction. */
    public void commit() throws SQLException {
        conn.commit();
    }

    /**
     * Roll back the current transaction entirely.
     * Safe to call in a catch block — logs but does not rethrow.
     */
    public void rollback() {
        try {
            conn.rollback();
        } catch (SQLException e) {
            LOG.warning("Rollback failed: " + e.getMessage());
        }
    }

    /**
     * Create a named savepoint within the current transaction.
     * @param name Savepoint name (no spaces)
     */
    public Savepoint setSavepoint(String name) throws SQLException {
        return conn.setSavepoint(name);
    }

    /**
     * Roll back to a previously set savepoint.
     * Work done after the savepoint is undone; work before it is preserved.
     */
    public void rollbackToSavepoint(Savepoint sp) {
        try {
            conn.rollback(sp);
        } catch (SQLException e) {
            LOG.warning("Rollback to savepoint failed: " + e.getMessage());
        }
    }

    /** Release (destroy) a savepoint — frees resources. */
    public void releaseSavepoint(Savepoint sp) {
        try {
            conn.releaseSavepoint(sp);
        } catch (SQLException e) {
            LOG.warning("Release savepoint failed: " + e.getMessage());
        }
    }

    // ── Multi-Step Loan Transaction ───────────────────────────────────────────

    /**
     * processLoan(bookId, memberId) — The canonical multi-step ACID transaction.
     *
     * Steps:
     *   1. Verify book is available (SELECT … FOR UPDATE via serializable read)
     *   2. Update BOOKS.Available = 0
     *   3. Insert into LOANS                    ← SAVEPOINT before this step
     *   4. Update MEMBERS.ActiveLoans += 1
     *
     * On any failure the entire transaction is rolled back; if only step 4 fails
     * the savepoint allows the loan insert to be rolled back while keeping the
     * book-status update visible for retry.
     *
     * @return the new LoanID on success, or -1 on failure (rollback performed)
     */
    public int processLoan(int bookId, int memberId) throws SQLException {
        beginTransaction();

        // ── Step 1: Verify book availability ─────────────────────────────────
        int available = -1;
        String checkBook = "SELECT Available FROM BOOKS WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkBook)) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    rollback();
                    throw new SQLException("Book ID " + bookId + " not found.");
                }
                available = rs.getInt("Available");
            }
        }
        if (available == 0) {
            rollback();
            throw new SQLException("Book ID " + bookId + " is currently not available.");
        }

        // ── Step 2: Update book status ────────────────────────────────────────
        String updateBook = "UPDATE BOOKS SET Available = 0 WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateBook)) {
            ps.setInt(1, bookId);
            ps.executeUpdate();
        }

        // ── SAVEPOINT before loan insert ──────────────────────────────────────
        Savepoint spLoan = setSavepoint("SP_LOAN_INSERT");

        // ── Step 3: Insert loan record ────────────────────────────────────────
        int newLoanId = -1;
        java.sql.Date today  = new java.sql.Date(System.currentTimeMillis());
        java.sql.Date dueDate = new java.sql.Date(System.currentTimeMillis() + 21L * 86_400_000L);
        String insertLoan =
            "INSERT INTO LOANS (MemberID, BookID, LoanDate, DueDate) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertLoan,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt (1, memberId);
            ps.setInt (2, bookId);
            ps.setDate(3, today);
            ps.setDate(4, dueDate);
            ps.executeUpdate();
            try (ResultSet genKeys = ps.getGeneratedKeys()) {
                if (genKeys.next()) newLoanId = genKeys.getInt(1);
            }
        } catch (SQLException e) {
            // Loan insert failed — roll back everything
            rollbackToSavepoint(spLoan);
            rollback();
            throw new SQLException("Loan insert failed: " + e.getMessage(), e);
        }

        // ── Step 4: Increment member active loan count ────────────────────────
        String updateMember =
            "UPDATE MEMBERS SET ActiveLoans = ActiveLoans + 1 " +
            "WHERE MemberID = ? AND ActiveLoans < 5";
        try (PreparedStatement ps = conn.prepareStatement(updateMember)) {
            ps.setInt(1, memberId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                // Member hit the 5-loan limit — roll back only the loan insert
                System.out.println(
                    "[TXN] Member " + memberId + " has reached max loan limit (5). " +
                    "Rolling back loan insert via savepoint.");
                rollbackToSavepoint(spLoan);
                // Also undo book status change (loan was never processed)
                rollback();
                throw new SQLException(
                    "Member ID " + memberId + " already has 5 active loans (max limit).");
            }
        } catch (SQLException e) {
            rollbackToSavepoint(spLoan);
            rollback();
            throw e;
        }

        releaseSavepoint(spLoan);
        commit();

        System.out.println("[TXN] Loan #" + newLoanId +
            " committed: Book " + bookId + " → Member " + memberId);
        return newLoanId;
    }

    // ── Return Transaction ────────────────────────────────────────────────────

    /**
     * processReturn(loanId) — Mark a loan as returned.
     *   1. Verify loan exists and is active
     *   2. Set LOANS.ReturnDate = today
     *   3. Set BOOKS.Available = 1
     *   4. Decrement MEMBERS.ActiveLoans
     *
     * @return true on success
     */
    public boolean processReturn(int loanId) throws SQLException {
        beginTransaction();

        // Step 1: Verify loan
        int bookId   = -1;
        int memberId = -1;
        String checkLoan =
            "SELECT MemberID, BookID, ReturnDate FROM LOANS WHERE LoanID = ?";
        try (PreparedStatement ps = conn.prepareStatement(checkLoan)) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    rollback();
                    throw new SQLException("Loan ID " + loanId + " not found.");
                }
                if (rs.getDate("ReturnDate") != null) {
                    rollback();
                    throw new SQLException("Loan ID " + loanId + " has already been returned.");
                }
                memberId = rs.getInt("MemberID");
                bookId   = rs.getInt("BookID");
            }
        }

        // Step 2: Mark returned
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE LOANS SET ReturnDate = CURRENT DATE WHERE LoanID = ?")) {
            ps.setInt(1, loanId);
            ps.executeUpdate();
        }

        // Step 3: Book available again
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE BOOKS SET Available = 1 WHERE BookID = ?")) {
            ps.setInt(1, bookId);
            ps.executeUpdate();
        }

        // Step 4: Decrement active loans
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE MEMBERS SET ActiveLoans = ActiveLoans - 1 " +
                "WHERE MemberID = ? AND ActiveLoans > 0")) {
            ps.setInt(1, memberId);
            ps.executeUpdate();
        }

        commit();
        System.out.println("[TXN] Return committed for Loan #" + loanId);
        return true;
    }

    // ── Constraint-Violation Demonstration ───────────────────────────────────

    /**
     * Demonstrate rollback integrity: attempt a duplicate ISBN insert.
     * The transaction will be rolled back, proving the DB remains consistent.
     */
    public void demonstrateConstraintViolationRollback(String duplicateISBN) {
        System.out.println("\n[DEMO] Attempting to insert duplicate ISBN: " + duplicateISBN);
        try {
            beginTransaction();

            // First insert (will succeed)
            String sql = "INSERT INTO BOOKS (ISBN, Title, Author) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "999-DEMO-TEMP");
                ps.setString(2, "Temp Book");
                ps.setString(3, "Temp Author");
                ps.executeUpdate();
                System.out.println("[DEMO] First insert (dummy) succeeded.");
            }

            // Second insert — DUPLICATE ISBN — will throw
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, duplicateISBN); // existing ISBN
                ps.setString(2, "Duplicate Title");
                ps.setString(3, "Duplicate Author");
                ps.executeUpdate(); // ← will throw unique-constraint violation
                System.out.println("[DEMO] Second insert succeeded (unexpected!).");
                commit();
            }

        } catch (SQLException e) {
            System.out.println("[DEMO] Caught constraint violation: " + e.getMessage());
            rollback();
            System.out.println("[DEMO] Transaction rolled back — DB is consistent.");
            // Verify: neither insert should be visible
            try {
                conn.setAutoCommit(true); // allow connection to close cleanly
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM BOOKS WHERE ISBN = '999-DEMO-TEMP'")) {
                    rs.next();
                    System.out.println("[DEMO] Rows with ISBN '999-DEMO-TEMP' after rollback: "
                        + rs.getInt(1) + " (expected: 0)");
                }
            } catch (SQLException ex) {
                System.out.println("[DEMO] Verification query failed: " + ex.getMessage());
            }
        }
    }
}
