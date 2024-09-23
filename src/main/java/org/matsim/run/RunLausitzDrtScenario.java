package org.matsim.run;

import org.matsim.application.MATSimApplication;

/**
 * Run the Lausitz DRT scenario policy case.
 */
public final class RunLausitzDrtScenario {

	private RunLausitzDrtScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzDrtScenario.class, args);
	}

}
