package library.business;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * BusinessLogic — All application-level database operations.
 *
 * Rules:
 *  - All DML uses PreparedStatement (no string concatenation)
 *  - Resources are closed via try-with-resources
 *  - READ operations use the shared connection (auto-commit OK)
 *  - WRITE operations that span multiple tables are handled by TransactionService
 */
public class BusinessLogic {

    private final Connection conn;

    public BusinessLogic(Connection conn) {
        this.conn = conn;
    }

    // ════════════════════════════════════════════════════════════════════════
    // MEMBER OPERATIONS
    // ════════════════════════════════════════════════════════════════════════

    /** Register a new library member. Returns the generated MemberID. */
    public int registerMember(String name, String email) throws SQLException {
        String sql = "INSERT INTO MEMBERS (Name, Email) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name.trim());
            ps.setString(2, email.trim().toLowerCase());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    System.out.println("[BL] Member registered: " + name + " (ID=" + id + ")");
                    return id;
                }
            }
        }
        throw new SQLException("Failed to retrieve generated MemberID.");
    }

    /** List all members. */
    public void listAllMembers() throws SQLException {
        String sql = "SELECT MemberID, Name, Email, ActiveLoans, JoinDate FROM MEMBERS ORDER BY MemberID";
        System.out.println("\n┌──────────────────────────────────────────────────────────────────────┐");
        System.out.printf ("│ %-4s  %-25s %-30s %-6s %-10s │%n",
                           "ID", "Name", "Email", "Loans", "Joined");
        System.out.println("├──────────────────────────────────────────────────────────────────────┤");
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf("│ %-4d  %-25s %-30s %-6d %-10s │%n",
                    rs.getInt   ("MemberID"),
                    rs.getString("Name"),
                    rs.getString("Email"),
                    rs.getInt   ("ActiveLoans"),
                    rs.getDate  ("JoinDate"));
            }
        }
        System.out.println("└──────────────────────────────────────────────────────────────────────┘");
    }

    // ════════════════════════════════════════════════════════════════════════
    // BOOK OPERATIONS
    // ════════════════════════════════════════════════════════════════════════

    /** Add a new book to the catalog. Returns generated BookID. */
    public int addBook(String isbn, String title, String author) throws SQLException {
        String sql = "INSERT INTO BOOKS (ISBN, Title, Author) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, isbn.trim());
            ps.setString(2, title.trim());
            ps.setString(3, author.trim());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    System.out.println("[BL] Book added: \"" + title + "\" (ID=" + id + ")");
                    return id;
                }
            }
        }
        throw new SQLException("Failed to retrieve generated BookID.");
    }

    /** List all books with availability status. */
    public void listAllBooks() throws SQLException {
        String sql = "SELECT BookID, ISBN, Title, Author, Available FROM BOOKS ORDER BY BookID";
        System.out.println("\n┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.printf ("│ %-4s  %-20s %-35s %-20s %-6s │%n",
                           "ID", "ISBN", "Title", "Author", "Avail");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.printf("│ %-4d  %-20s %-35s %-20s %-6s │%n",
                    rs.getInt   ("BookID"),
                    rs.getString("ISBN"),
                    truncate(rs.getString("Title"),  35),
                    truncate(rs.getString("Author"), 20),
                    rs.getInt("Available") == 1 ? "YES" : "NO");
            }
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
    }

    /** Look up a book by ISBN (indexed lookup). */
    public void findBookByISBN(String isbn) throws SQLException {
        String sql = "SELECT * FROM BOOKS WHERE ISBN = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.printf("[BL] Found: ID=%d | \"%s\" by %s | Available: %s%n",
                        rs.getInt   ("BookID"),
                        rs.getString("Title"),
                        rs.getString("Author"),
                        rs.getInt   ("Available") == 1 ? "Yes" : "No");
                } else {
                    System.out.println("[BL] No book found with ISBN: " + isbn);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOAN QUERY OPERATIONS
    // ════════════════════════════════════════════════════════════════════════

    /** List all active (not returned) loans for a given member. */
    public void listActiveLoansByMember(int memberId) throws SQLException {
        String sql =
            "SELECT L.LoanID, B.Title, B.ISBN, L.LoanDate, L.DueDate " +
            "  FROM LOANS L " +
            "  JOIN BOOKS B ON L.BookID = B.BookID " +
            " WHERE L.MemberID = ? AND L.ReturnDate IS NULL " +
            " ORDER BY L.DueDate";
        System.out.println("\n  Active Loans for Member #" + memberId + ":");
        System.out.printf ("  %-6s  %-35s %-12s %-12s%n", "LoanID", "Title", "LoanDate", "DueDate");
        System.out.println("  " + "─".repeat(70));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    System.out.printf("  %-6d  %-35s %-12s %-12s%n",
                        rs.getInt   ("LoanID"),
                        truncate(rs.getString("Title"), 35),
                        rs.getDate  ("LoanDate"),
                        rs.getDate  ("DueDate"));
                }
                if (!found) System.out.println("  (no active loans)");
            }
        }
    }

    /** List all overdue books (DueDate < today AND ReturnDate IS NULL). */
    public void listOverdueLoans() throws SQLException {
        String sql =
            "SELECT L.LoanID, M.Name, B.Title, L.LoanDate, L.DueDate, " +
            "       {fn TIMESTAMPDIFF(SQL_TSI_DAY, CAST(L.DueDate AS TIMESTAMP), CURRENT_TIMESTAMP)} AS DaysOverdue " +
            "  FROM LOANS L " +
            "  JOIN MEMBERS M ON L.MemberID = M.MemberID " +
            "  JOIN BOOKS   B ON L.BookID   = B.BookID " +
            " WHERE L.ReturnDate IS NULL AND L.DueDate < CURRENT DATE " +
            " ORDER BY L.DueDate";
        System.out.println("\n  ⚠  Overdue Loans:");
        System.out.printf ("  %-6s  %-20s %-30s %-10s %-10s %-8s%n",
                           "LoanID", "Member", "Title", "LoanDate", "DueDate", "DaysOver");
        System.out.println("  " + "─".repeat(90));
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            boolean found = false;
            while (rs.next()) {
                found = true;
                System.out.printf("  %-6d  %-20s %-30s %-10s %-10s %-8d%n",
                    rs.getInt   ("LoanID"),
                    truncate(rs.getString("Name"),  20),
                    truncate(rs.getString("Title"), 30),
                    rs.getDate  ("LoanDate"),
                    rs.getDate  ("DueDate"),
                    rs.getInt   ("DaysOverdue"));
            }
            if (!found) System.out.println("  (no overdue loans — great!)");
        }
    }

    /** List all loans (full history). */
    public void listAllLoans() throws SQLException {
        String sql =
            "SELECT L.LoanID, M.Name, B.Title, L.LoanDate, L.DueDate, L.ReturnDate " +
            "  FROM LOANS L " +
            "  JOIN MEMBERS M ON L.MemberID = M.MemberID " +
            "  JOIN BOOKS   B ON L.BookID   = B.BookID " +
            " ORDER BY L.LoanID";
        System.out.println("\n  All Loans:");
        System.out.printf ("  %-6s  %-20s %-30s %-10s %-10s %-10s%n",
                           "LoanID", "Member", "Title", "LoanDate", "DueDate", "Returned");
        System.out.println("  " + "─".repeat(95));
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Date ret = rs.getDate("ReturnDate");
                System.out.printf("  %-6d  %-20s %-30s %-10s %-10s %-10s%n",
                    rs.getInt   ("LoanID"),
                    truncate(rs.getString("Name"),  20),
                    truncate(rs.getString("Title"), 30),
                    rs.getDate  ("LoanDate"),
                    rs.getDate  ("DueDate"),
                    ret != null ? ret.toString() : "(active)");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ════════════════════════════════════════════════════════════════════════

    /** Retrieve all book IDs + ISBNs as simple list for display. */
    public List<String> getAllBookSummaries() throws SQLException {
        List<String> list = new ArrayList<>();
        String sql = "SELECT BookID, ISBN, Title, Available FROM BOOKS ORDER BY BookID";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(String.format("[%d] %s — %s (%s)",
                    rs.getInt("BookID"),
                    rs.getString("ISBN"),
                    rs.getString("Title"),
                    rs.getInt("Available") == 1 ? "Available" : "Checked Out"));
            }
        }
        return list;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
