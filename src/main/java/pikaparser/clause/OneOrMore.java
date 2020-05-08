package pikaparser.clause;

import pikaparser.memotable.Match;
import pikaparser.memotable.MemoKey;
import pikaparser.memotable.MemoTable;

public class OneOrMore extends Clause {
    OneOrMore(Clause subClause) {
        super(new Clause[] { subClause });
    }

    public OneOrMore(Clause[] subClauses) {
        super(subClauses);
    }

    @Override
    public void testWhetherCanMatchZeroChars() {
        if (subClauses[0].canMatchZeroChars) {
            canMatchZeroChars = true;
        }
    }

    @Override
    public Match match(MatchDirection matchDirection, MemoTable memoTable, MemoKey memoKey, String input) {
        var subClause = subClauses[0];
        var subClauseMemoKey = new MemoKey(subClause, memoKey.startPos);
        var subClauseMatch = matchDirection == MatchDirection.TOP_DOWN
                // Match lex rules top-down, which avoids creating memo entries for unused terminals.
                ? subClause.match(MatchDirection.TOP_DOWN, memoTable, subClauseMemoKey, input)
                // Otherwise matching bottom-up -- just look in the memo table for subclause matches
                : memoTable.lookUpBestMatch(subClauseMemoKey, input, memoKey);
        if (subClauseMatch == null) {
            return null;
        }

        // Perform right-recursive match of the same OneOrMore clause, so that the memo table doesn't
        // fill up with O(N^2) entries in the number of subclause matches N.
        // If there are two or more matches, tailMatch will be non-null.
        var tailMatchMemoKey = new MemoKey(this, memoKey.startPos + subClauseMatch.len);
        var tailMatch = matchDirection == MatchDirection.TOP_DOWN
                ? this.match(MatchDirection.TOP_DOWN, memoTable, tailMatchMemoKey, input)
                : memoTable.lookUpBestMatch(tailMatchMemoKey, input, memoKey);

        // Return a new (right-recursive) match
        return tailMatch == null // 
                ? new Match(memoKey, /* firstMatchingSubClauseIdx = */ 0, /* len = */ subClauseMatch.len,
                        new Match[] { subClauseMatch })
                : new Match(memoKey, /* firstMatchingSubClauseIdx = */ 0,
                        /* len = */ subClauseMatch.len + tailMatch.len, new Match[] { subClauseMatch, tailMatch });
    }

    @Override
    public String toString() {
        if (toStringCached == null) {
            var buf = new StringBuilder();
            buf.append('(');
            if (subClauseASTNodeLabels != null && subClauseASTNodeLabels[0] != null) {
                buf.append(subClauseASTNodeLabels[0]);
                buf.append(':');
            }
            buf.append(subClauses[0].toString());
            buf.append(")+");
            toStringCached = buf.toString();
        }
        return toStringCached;
    }
}
