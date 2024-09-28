package org.mitre.synthea.engine;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;

/**
 * ReactionProbabilities is a structure to capture the kind and severity of reaction for a
 * given AllergyIntolerance.
 */
public class ReactionProbabilities implements Serializable {
  private static final String SEVERE = "severe";
  private static final String MODERATE = "moderate";
  private static final String MILD = "mild";
  // Used in the case that the reaction does not happen for the individual
  private static final String NONE = "none";

  private HealthRecord.Code reaction;
  private List<SeverityProbability> possibleSeverities;
  private EnumeratedDistribution<String> severityDistribution;

  public HealthRecord.Code getReaction() {
    return reaction;
  }

  public void setReaction(HealthRecord.Code reaction) {
    this.reaction = reaction;
  }

  public static class SeverityProbability implements Serializable {
    private String level;
    private float value;

    public SeverityProbability(String level, float value) {
      this.level = level;
      this.value = value;
    }

    public String getLevel() {
      return level;
    }

    public void setLevel(String level) {
      this.level = level;
    }

    public float getValue() {
      return value;
    }

    public void setValue(float value) {
      this.value = value;
    }
  }

  public ReactionProbabilities(HealthRecord.Code code,
                               List<SeverityProbability> possibleReactions) {
    this.reaction = code;
    this.possibleSeverities = possibleReactions;
  }

  public boolean isPopulated() {
    return !this.possibleSeverities.isEmpty();
  }

  /**
   * This will validate the reaction probabilities and generate the underlying objects used
   * to get weighted samples of reaction severities.
   */
  public void buildReactionDistributions() {
    if (!this.validate()) {
      throw new IllegalStateException("Invalid distribution values specified");
    }
    List probPairs = this.possibleSeverities.stream()
          .map(sp -> new Pair(sp.getLevel(), (double) sp.getValue()))
          .collect(Collectors.toList());
    this.severityDistribution = new EnumeratedDistribution<String>(probPairs);
  }

  public boolean validate() {
    // less than 1.001 to deal with floating point rounding issues
    return possibleSeverities.stream().mapToDouble(sp -> sp.getValue()).sum() <= 1.001;
  }

  /**
   * Generate a severity for the reaction of an allergy or intolerance. This uses the passed in
   * person for the random seed. There is a possibility that the reaction will not be selected
   * for an individual. In that case, the method returns null.
   * @param person used for random seed
   * @return a ReactionSeverity value to indicate severity or null
   */
  public HealthRecord.ReactionSeverity generateSeverity(Person person) {
    if (this.isPopulated() && this.severityDistribution == null) {
      this.buildReactionDistributions();
    }
    this.severityDistribution.reseedRandomGenerator(person.randLong());
    String severity = this.severityDistribution.sample();
    switch (severity) {
      case SEVERE:
        return HealthRecord.ReactionSeverity.SEVERE;
      case MODERATE:
        return HealthRecord.ReactionSeverity.MODERATE;
      case MILD:
        return HealthRecord.ReactionSeverity.MILD;
      case NONE:
        // do nothing
        return null;
      default:
        // should never get here
    }
    //should never get here
    return null;
  }
}
