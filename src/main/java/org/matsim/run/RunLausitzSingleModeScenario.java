package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzSingleModeScenario;

/**
 * Run the Lausitz single mode scenario policy case.
 */
public final class RunLausitzSingleModeScenario {

	private RunLausitzSingleModeScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzSingleModeScenario.class, args);
	}

}
