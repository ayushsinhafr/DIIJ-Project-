package library.ui;

import library.benchmark.PerformanceEvaluator;
import library.business.BusinessLogic;
import library.connection.ConnectionManager;
import library.transaction.TransactionService;

import java.sql.*;
import java.util.List;
import java.util.Scanner;

/**
 * MainApp — CLI orchestrator for the Library Loan Management System.
 *
 * Menu:
 * 1. View all members
 * 2. Register a new member
 * 3. View all books
 * 4. Add a new book
 * 5. View all loans
 * 6. View active loans by member
 * 7. View overdue loans
 * 8. Process a loan (borrow book)
 * 9. Process a return
 * 10. Find book by ISBN
 * 11. Demo: transaction rollback (constraint violation)
 * 12. Run performance benchmarks
 * 0. Exit
 */
public class MainApp {

    private static final String DIVIDER = "═".repeat(60);
    private static final String THIN = "─".repeat(60);

    public static void main(String[] args) {
        printBanner();

        ConnectionManager cm = new ConnectionManager();
        BusinessLogic bl = null;
        TransactionService txn = null;
        PerformanceEvaluator pe = null;

        try {
            // ── Initialise DB ─────────────────────────────────────────────
            cm.initialize();
            Connection conn = cm.getConnection();
            conn.setAutoCommit(true); // read operations use shared conn

            bl = new BusinessLogic(conn);
            pe = new PerformanceEvaluator(cm);

            // ── CLI Loop ──────────────────────────────────────────────────
            Scanner sc = new Scanner(System.in);
            boolean running = true;

            while (running) {
                printMenu();
                System.out.print("  Enter choice: ");
                String input = sc.nextLine().trim();

                try {
                    switch (input) {
                        case "1" -> bl.listAllMembers();
                        case "2" -> registerMember(sc, bl);
                        case "3" -> bl.listAllBooks();
                        case "4" -> addBook(sc, bl);
                        case "5" -> bl.listAllLoans();
                        case "6" -> activeLoansByMember(sc, bl);
                        case "7" -> bl.listOverdueLoans();
                        case "8" -> processLoan(sc, cm, bl);
                        case "9" -> processReturn(sc, cm);
                        case "10" -> findByISBN(sc, bl);
                        case "11" -> demoRollback(cm);
                        case "12" -> pe.runAll();
                        case "0" -> running = false;
                        default -> System.out.println("  [!] Unknown option. Please try again.");
                    }
                } catch (SQLException e) {
                    System.out.println("\n  [ERROR] " + e.getMessage());
                    System.out.println("  SQLState: " + e.getSQLState());
                }

                if (running) {
                    System.out.println("\n" + THIN);
                    System.out.print("  Press ENTER to continue...");
                    sc.nextLine();
                }
            }

            System.out.println("\n  Goodbye! Shutting down...");

        } catch (SQLException e) {
            System.err.println("[FATAL] Database error during startup: " + e.getMessage());
            System.err.println("SQLState: " + e.getSQLState());
        } finally {
            cm.shutdown();
        }
    }

    // ── Menu Actions ──────────────────────────────────────────────────────────

    private static void registerMember(Scanner sc, BusinessLogic bl) throws SQLException {
        System.out.println("\n  == Register New Member ==");
        System.out.print("  Name  : ");
        String name = sc.nextLine().trim();
        System.out.print("  Email : ");
        String email = sc.nextLine().trim();
        if (name.isEmpty() || email.isEmpty()) {
            System.out.println("  [!] Name and email are required.");
            return;
        }
        int id = bl.registerMember(name, email);
        System.out.println("  ✓ Member registered with ID: " + id);
    }

    private static void addBook(Scanner sc, BusinessLogic bl) throws SQLException {
        System.out.println("\n  == Add New Book ==");
        System.out.print("  ISBN   : ");
        String isbn = sc.nextLine().trim();
        System.out.print("  Title  : ");
        String title = sc.nextLine().trim();
        System.out.print("  Author : ");
        String author = sc.nextLine().trim();
        if (isbn.isEmpty() || title.isEmpty() || author.isEmpty()) {
            System.out.println("  [!] All fields are required.");
            return;
        }
        int id = bl.addBook(isbn, title, author);
        System.out.println("  ✓ Book added with ID: " + id);
    }

    private static void activeLoansByMember(Scanner sc, BusinessLogic bl) throws SQLException {
        System.out.print("\n  Enter Member ID: ");
        try {
            int mid = Integer.parseInt(sc.nextLine().trim());
            bl.listActiveLoansByMember(mid);
        } catch (NumberFormatException e) {
            System.out.println("  [!] Invalid member ID.");
        }
    }

    private static void processLoan(Scanner sc, ConnectionManager cm, BusinessLogic bl)
            throws SQLException {
        System.out.println("\n  == Process Loan (Borrow Book) ==");

        // Show available books
        System.out.println("  Available books:");
        List<String> books = bl.getAllBookSummaries();
        books.forEach(b -> System.out.println("    " + b));

        int bookId;
        int memberId;
        try {
            System.out.print("\n  Enter Book ID   : ");
            bookId = Integer.parseInt(sc.nextLine().trim());
            System.out.print("  Enter Member ID : ");
            memberId = Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            System.out.println("  [!] Invalid numeric input.");
            return;
        }

        // Use a dedicated connection for the transaction
        try (Connection txConn = cm.openNewConnection()) {
            TransactionService txn = new TransactionService(txConn);
            int loanId = txn.processLoan(bookId, memberId);
            System.out.println("  ✓ Loan #" + loanId + " created successfully!");
        }
    }

    private static void processReturn(Scanner sc, ConnectionManager cm) throws SQLException {
        System.out.println("\n  == Process Return ==");
        System.out.print("  Enter Loan ID: ");
        try {
            int loanId = Integer.parseInt(sc.nextLine().trim());
            try (Connection txConn = cm.openNewConnection()) {
                TransactionService txn = new TransactionService(txConn);
                txn.processReturn(loanId);
                System.out.println("  ✓ Book returned successfully for Loan #" + loanId);
            }
        } catch (NumberFormatException e) {
            System.out.println("  [!] Invalid loan ID.");
        }
    }

    private static void findByISBN(Scanner sc, BusinessLogic bl) throws SQLException {
        System.out.print("\n  Enter ISBN: ");
        String isbn = sc.nextLine().trim();
        bl.findBookByISBN(isbn);
    }

    private static void demoRollback(ConnectionManager cm) throws SQLException {
        System.out.println("\n  == Transaction Rollback Demonstration ==");
        System.out.println("  This will attempt to insert a duplicate ISBN, then roll back.");
        System.out.println("  Existing ISBN to duplicate: 978-0-13-110362-7 (C Programming Language)");
        try (Connection txConn = cm.openNewConnection()) {
            TransactionService txn = new TransactionService(txConn);
            txn.demonstrateConstraintViolationRollback("978-0-13-110362-7");
        }
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    private static void printBanner() {
        System.out.println();
        System.out.println(DIVIDER);
        System.out.println("  ██╗     ██╗██████╗ ██████╗  █████╗ ██████╗ ██╗   ██╗");
        System.out.println("  ██║     ██║██╔══██╗██╔══██╗██╔══██╗██╔══██╗╚██╗ ██╔╝");
        System.out.println("  ██║     ██║██████╔╝██████╔╝███████║██████╔╝ ╚████╔╝ ");
        System.out.println("  ██║     ██║██╔══██╗██╔══██╗██╔══██║██╔══██╗  ╚██╔╝  ");
        System.out.println("  ███████╗██║██████╔╝██║  ██║██║  ██║██║  ██║   ██║   ");
        System.out.println("  ╚══════╝╚═╝╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ");
        System.out.println();
        System.out.println("   Library Loan Management System — Apache Derby + JDBC");
        System.out.println("   Transaction Management & Performance Evaluation Edition");
        System.out.println(DIVIDER);
        System.out.println();
    }

    private static void printMenu() {
        System.out.println();
        System.out.println(DIVIDER);
        System.out.println("  MAIN MENU");
        System.out.println(THIN);
        System.out.println("  MEMBERS");
        System.out.println("    [1] View all members");
        System.out.println("    [2] Register new member");
        System.out.println();
        System.out.println("  BOOKS");
        System.out.println("    [3] View all books");
        System.out.println("    [4] Add new book");
        System.out.println("   [10] Find book by ISBN");
        System.out.println();
        System.out.println("  LOANS");
        System.out.println("    [5] View all loans");
        System.out.println("    [6] Active loans by member");
        System.out.println("    [7] View overdue loans");
        System.out.println("    [8] Process loan (borrow)");
        System.out.println("    [9] Process return");
        System.out.println();
        System.out.println("  DEMONSTRATIONS & BENCHMARKS");
        System.out.println("   [11] Demo: transaction rollback");
        System.out.println("   [12] Run performance benchmarks");
        System.out.println();
        System.out.println("    [0] Exit");
        System.out.println(THIN);
    }
}
