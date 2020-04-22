package pikaparser.grammar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import pikaparser.clause.ASTNodeLabel;
import pikaparser.clause.Clause;
import pikaparser.clause.First;
import pikaparser.clause.Longest;
import pikaparser.clause.RuleRef;
import pikaparser.grammar.Rule.Associativity;
import pikaparser.memotable.Match;
import pikaparser.memotable.MemoTable;

public class Grammar {
    public final List<Clause> allClauses;
    public Clause lexClause;
    public Map<String, Rule> ruleNameWithPrecedenceToRule;

    public Grammar(List<Rule> rules) {
        this(/* lexRuleName = */ null, rules);
    }

    public Grammar(String lexRuleName, List<Rule> rules) {
        if (rules.size() == 0) {
            throw new IllegalArgumentException("Grammar must consist of at least one rule");
        }

        // Group rules by name
        Map<String, List<Rule>> ruleNameToRules = new HashMap<>();
        for (var rule : rules) {
            if (rule.ruleName == null) {
                throw new IllegalArgumentException("All rules must be named");
            }
            if (rule.clause instanceof RuleRef && ((RuleRef) rule.clause).refdRuleName.equals(rule.ruleName)) {
                // Make sure rule doesn't refer only to itself
                throw new IllegalArgumentException(
                        "Rule cannot refer to only itself: " + rule.ruleName + "[" + rule.precedence + "]");
            }
            var rulesWithName = ruleNameToRules.get(rule.ruleName);
            if (rulesWithName == null) {
                ruleNameToRules.put(rule.ruleName, rulesWithName = new ArrayList<>());
            }
            rulesWithName.add(rule);

            // Make sure there are no cycles (to simplify other recursive routines)
            checkNoCycles(rule.clause, rule.ruleName, new HashSet<Clause>());
        }
        List<Rule> allRules = new ArrayList<>(rules);
        Map<String, String> ruleNameToLowestPrecedenceLevelRuleName = new HashMap<>();
        for (var ent : ruleNameToRules.entrySet()) {
            // Rewrite rules that have multiple precedence levels, as described in the paper
            var rulesWithName = ent.getValue();
            if (rulesWithName.size() > 1) {
                var ruleName = ent.getKey();
                handlePrecedence(ruleName, rulesWithName, ruleNameToLowestPrecedenceLevelRuleName);
            }
        }

        // Lift AST node labels into their parent clause' subClauseASTNodeLabel array, or into the rule
        for (var rule : allRules) {
            // Lift AST node label from clause into astNodeLabel field in rule
            while (rule.clause instanceof ASTNodeLabel) {
                if (rule.astNodeLabel == null) {
                    rule.astNodeLabel = ((ASTNodeLabel) rule.clause).astNodeLabel;
                }
                rule.clause = rule.clause.subClauses[0];
            }

            // Lift AST node labels from subclauses into subClauseASTNodeLabels array in parent
            liftASTNodeLabels(rule.clause);
        }

        // If there is more than one precedence level for a rule, the handlePrecedence call above modifies
        // rule names to include a precedence suffix, and also adds an all-precedence selector clause with the
        // orgininal rule name. All rule names should now be unique.
        ruleNameWithPrecedenceToRule = new HashMap<>();
        for (var rule : allRules) {
            // The handlePrecedence call above added the precedence to the rule name as a suffix
            if (ruleNameWithPrecedenceToRule.put(rule.ruleName, rule) != null) {
                // Should not happen
                throw new IllegalArgumentException("Duplicate rule name " + rule.ruleName);
            }
        }

        // Intern clauses based on their toString() value, coalescing shared sub-clauses into a DAG, so that
        // effort is not wasted parsing different instances of the same clause multiple times, and so that
        // when a subclause matches, all parent clauses will be added to the active set in the next iteration.
        // Also causes the toString() values to be cached, so that after RuleRefs are replaced with direct
        // Clause references, toString() doesn't get stuck in an infinite loop.
        Map<String, Clause> toStringToClause = new HashMap<>();
        Set<Clause> internVisited = new HashSet<>();
        for (var rule : allRules) {
            rule.intern(toStringToClause, internVisited);
        }

        // Resolve each RuleRef into a direct reference to the referenced clause
        Set<Clause> ruleClausesVisited = new HashSet<>();
        for (var rule : allRules) {
            rule.resolveRuleRefs(ruleNameWithPrecedenceToRule, ruleNameToLowestPrecedenceLevelRuleName,
                    ruleClausesVisited);
        }

        if (lexRuleName != null) {
            // Find the toplevel lex rule, if lexRuleName is specified
            var lexRule = ruleNameWithPrecedenceToRule.get(lexRuleName);
            if (lexRule == null) {
                throw new IllegalArgumentException("Unknown lex rule name: " + lexRuleName);
            }
            // Check the lex rule does not contain any cycles
            lexRule.checkNoCycles();
            lexClause = lexRule.clause;
        }

        // Find clauses reachable from the toplevel clause, in reverse topological order.
        // Clauses can form a DAG structure, via RuleRef.
        allClauses = new ArrayList<Clause>();
        HashSet<Clause> allClausesVisited = new HashSet<Clause>();
        for (var rule : allRules) {
            rule.findReachableClauses(allClausesVisited, allClauses);
        }

        // Find clauses that always match zero or more characters, e.g. FirstMatch(X | Nothing).
        // allClauses is in reverse topological order, i.e. bottom-up
        for (Clause clause : allClauses) {
            clause.testWhetherCanMatchZeroChars();
        }

        // Find seed parent clauses (in the case of Seq, this depends upon alwaysMatches being set in the prev step)
        for (var clause : allClauses) {
            clause.backlinkToSeedParentClauses();
        }
    }

    /**
     * Label subclause positions with the AST node label from any {@link CreateASTNode} nodes in each subclause
     * position.
     */
    private static void liftASTNodeLabels(Clause clause) {
        for (int subClauseIdx = 0; subClauseIdx < clause.subClauses.length; subClauseIdx++) {
            Clause subClause = clause.subClauses[subClauseIdx];
            if (subClause instanceof ASTNodeLabel) {
                // Copy any AST node labels from subclause node to subClauseASTNodeLabels array within the parent
                var subClauseASTNodeLabel = ((ASTNodeLabel) subClause).astNodeLabel;
                if (subClauseASTNodeLabel != null) {
                    if (clause.subClauseASTNodeLabels == null) {
                        // Alloc array for subclause node labels, if not already done
                        clause.subClauseASTNodeLabels = new String[clause.subClauses.length];
                    }
                    if (clause.subClauseASTNodeLabels[subClauseIdx] == null) {
                        // Update subclause label, if it hasn't already been labeled
                        clause.subClauseASTNodeLabels[subClauseIdx] = subClauseASTNodeLabel;
                    }
                } else {
                    throw new IllegalArgumentException(ASTNodeLabel.class.getSimpleName() + " is null");
                }
                // Remove the ASTNodeLabel node 
                clause.subClauses[subClauseIdx] = subClause.subClauses[0];
            }
            // Recurse
            liftASTNodeLabels(subClause);
        }
    }

    private static void checkNoCycles(Clause clause, String selfRefRuleName, Set<Clause> visited) {
        if (visited.add(clause)) {
            for (Clause subClause : clause.subClauses) {
                checkNoCycles(subClause, selfRefRuleName, visited);
            }
        } else {
            throw new IllegalArgumentException(
                    "Rules should not contain cycles when they are created: " + selfRefRuleName);
        }
    }

    private static int countRuleSelfReferences(Clause clause, String ruleName) {
        if (clause instanceof RuleRef && ((RuleRef) clause).refdRuleName.equals(ruleName)) {
            return 1;
        } else {
            var numSelfRefs = 0;
            for (var subClause : clause.subClauses) {
                numSelfRefs += countRuleSelfReferences(subClause, ruleName);
            }
            return numSelfRefs;
        }
    }

    private static int rewriteSelfReferences(Clause clause, Associativity associativity, int numSelfRefsSoFar,
            int numSelfRefs, String selfRefRuleName, String currPrecRuleName, String nextHighestPrecRuleName) {
        if (clause instanceof RuleRef && ((RuleRef) clause).refdRuleName.equals(selfRefRuleName)) {
            // For leftmost self-ref of a left-associative rule, or rightmost self-ref of a right-associative rule,
            // replace self-reference with a reference to the same precedence level; for all other self-references
            // and when there is no specified precedence, replace self-references with a reference to the next
            // highest precedence level
            var referToCurrPrecLevel = associativity == Associativity.LEFT && numSelfRefsSoFar == 0
                    || associativity == Associativity.RIGHT && numSelfRefsSoFar == numSelfRefs - 1;
            ((RuleRef) clause).refdRuleName = referToCurrPrecLevel ? currPrecRuleName : nextHighestPrecRuleName;
            return numSelfRefsSoFar + 1;
        } else {
            var numSelfRefsCumul = numSelfRefsSoFar;
            for (var subClause : clause.subClauses) {
                numSelfRefsCumul = rewriteSelfReferences(subClause, associativity, numSelfRefsCumul, numSelfRefs,
                        selfRefRuleName, currPrecRuleName, nextHighestPrecRuleName);
            }
            return numSelfRefsCumul;
        }
    }

    private static boolean rewriteSelfReference(Clause clause, String selfRefRuleName, String currPrecRuleName,
            String nextHighestPrecRuleName) {
        for (int i = 0; i < clause.subClauses.length; i++) {
            var subClause = clause.subClauses[i];
            if (subClause instanceof RuleRef && ((RuleRef) subClause).refdRuleName.equals(selfRefRuleName)) {
                clause.subClauses[i] = new First(new RuleRef(currPrecRuleName),
                        new RuleRef(nextHighestPrecRuleName));
                // Break out of recursion
                return true;
            } else {
                if (rewriteSelfReference(subClause, selfRefRuleName, currPrecRuleName, nextHighestPrecRuleName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Convert left-recursive rules to use Longest, as described in the paper. */
    private static void handlePrecedence(String ruleNameWithoutPrecedence, List<Rule> rules,
            Map<String, String> ruleNameToLowestPrecedenceLevelRuleName) {
        // Check there are no duplicate precedence levels
        var precedenceToRule = new TreeMap<Integer, Rule>();
        for (var rule : rules) {
            if (precedenceToRule.put(rule.precedence, rule) != null) {
                throw new IllegalArgumentException("Multiple rules with name " + ruleNameWithoutPrecedence
                        + " and precedence " + rule.precedence);
            }
        }
        // Get rules in ascending order of precedence
        var precedenceOrder = new ArrayList<>(precedenceToRule.values());

        // Rename rules to include precedence level
        var numPrecedenceLevels = rules.size();
        for (int precedenceIdx = 0; precedenceIdx < numPrecedenceLevels; precedenceIdx++) {
            // Since there is more than one precedence level, update rule name to include precedence
            var rule = precedenceOrder.get(precedenceIdx);
            rule.ruleName += "[" + rule.precedence + "]";
        }

        // Transform grammar rule to handle precence
        for (int precedenceIdx = 0; precedenceIdx < numPrecedenceLevels; precedenceIdx++) {
            var rule = precedenceOrder.get(precedenceIdx);

            // Count the number of self-reference operands
            var numSelfRefs = countRuleSelfReferences(rule.clause, ruleNameWithoutPrecedence);

            var currPrecRuleName = rule.ruleName;
            var nextHighestPrecRuleName = precedenceOrder.get((precedenceIdx + 1) % numPrecedenceLevels).ruleName;

            // If a rule has 2+ self-references, and rule is associative, need rewrite rule for associativity
            if (numSelfRefs >= 2) {
                // For left-associative rules, need to set up a left-recursive alternative and a non-left-recursive
                // alternative, wrapped in a Longest clause, as described in the paper
                if (rule.associativity == Associativity.LEFT) {
                    rule.clause = new Longest(rule.clause, rule.clause.duplicate());
                }
                rewriteSelfReferences(rule.clause, rule.associativity, 0, numSelfRefs, ruleNameWithoutPrecedence,
                        currPrecRuleName, nextHighestPrecRuleName);
            } else if (numSelfRefs == 1) {
                // If there is only one self-reference, replace it with a single First clause that defers to
                // the next highest level of precedence if a match fails at the current level of precedence
                rewriteSelfReference(rule.clause, ruleNameWithoutPrecedence, currPrecRuleName,
                        nextHighestPrecRuleName);
            }

            // Defer to next highest level of precedence if the rule doesn't match, except at the highest level of
            // precedence, which is assumed to be a precedence-breaking pattern (like parentheses), so should not
            // defer back to the lowest precedence level unless the pattern itself matches
            if (precedenceIdx < numPrecedenceLevels - 1) {
                rule.clause = new First(rule.clause, new RuleRef(nextHighestPrecRuleName));
            }
        }

        // Map the bare rule name (without precedence suffix) to the lowest precedence level rule name
        ruleNameToLowestPrecedenceLevelRuleName.put(ruleNameWithoutPrecedence, precedenceOrder.get(0).ruleName);
    }

    public Rule getRule(String ruleNameWithPrecedence) {
        var rule = ruleNameWithPrecedenceToRule.get(ruleNameWithPrecedence);
        if (rule == null) {
            throw new IllegalArgumentException("Unknown rule name: " + ruleNameWithPrecedence);
        }
        return rule;
    }

    /**
     * Get the {@link Match} entries for all nonoverlapping matches of the named rule, obtained by greedily matching
     * from the beginning of the string, then looking for the next match after the end of the current match.
     */
    public List<Match> getNonOverlappingMatches(MemoTable memoTable, String ruleName, int precedence) {
        var clause = getRule(ruleName).clause;
        return memoTable.getNonOverlappingMatches(clause);
    }

    /**
     * Get the {@link Match} entries for all nonoverlapping matches of the named rule, obtained by greedily matching
     * from the beginning of the string, then looking for the next match after the end of the current match.
     */
    public List<Match> getNonOverlappingMatches(MemoTable memoTable, String ruleName) {
        return getNonOverlappingMatches(memoTable, ruleName, 0);
    }

    /**
     * Get the {@link Match} entries for all postions where a match was queried for the named rule, but there was no
     * match.
     */
    public List<Integer> getNonMatches(MemoTable memoTable, String ruleName, int precedence) {
        var clause = getRule(ruleName).clause;
        return memoTable.getNonMatchPositions(clause);
    }

    /**
     * Get the {@link Match} entries for all postions where a match was queried for the named rule, but there was no
     * match.
     */
    public List<Integer> getNonMatches(MemoTable memoTable, String ruleName) {
        return getNonMatches(memoTable, ruleName, 0);
    }
}