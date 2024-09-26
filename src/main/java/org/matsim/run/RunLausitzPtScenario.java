package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzPtScenario;

/**
 * Run the Lausitz PT scenario policy case.
 */
public final class RunLausitzPtScenario {

	private RunLausitzPtScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzPtScenario.class, args);
	}

}
