package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzMorningEveningNoOpeningTimesActivitiesScenario;

/**
 * Run the Lausitz scenario with separate morning and evening act types + no opening times for acts.
 */
public final class RunLausitzMorningEveningNoOpeningTimesActivitiesScenario {

	private RunLausitzMorningEveningNoOpeningTimesActivitiesScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzMorningEveningNoOpeningTimesActivitiesScenario.class, args);
	}

}
