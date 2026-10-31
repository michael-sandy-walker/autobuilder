package org.shelfkit.lending;

/** Whether a title may go out to a member. */
public final class LendingPolicy {

    public enum Verdict { ALLOWED, ALREADY_ON_LOAN, LIMIT_REACHED }

    private final int maxActiveLoans;

    public LendingPolicy(int maxActiveLoans) {
        if (maxActiveLoans < 1) {
            throw new IllegalArgumentException("a member may hold at least one loan");
        }
        this.maxActiveLoans = maxActiveLoans;
    }

    /** A branch library's policy: three active loans per member. */
    public static LendingPolicy standard() {
        return new LendingPolicy(3);
    }

    /** A title on loan is refused first, whatever the member holds. */
    public Verdict decide(boolean titleOnLoan, int membersActiveLoans) {
        if (titleOnLoan) return Verdict.ALREADY_ON_LOAN;
        return membersActiveLoans >= maxActiveLoans ? Verdict.LIMIT_REACHED : Verdict.ALLOWED;
    }
}
