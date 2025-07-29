package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzSpeedReductionScenario;

/**
 * Run the Lausitz speed reduction scenario policy case.
 */
public final class RunLausitzSpeedReductionScenario {

	private RunLausitzSpeedReductionScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzSpeedReductionScenario.class, args);
	}

}
