package org.matsim.run.analysis;

final class HeadersKN{
	public static final String MODE_SEQ = "modeSeq";
	public static final String PERSON_ID = "personId";
	public static final String INCOME = "income";
	public static final String SCORE = "score";
	public static final String UTL_OF_MONEY = "utlOfMoney";
	public static final String TTIME = "ttime";
	public static final String ACT_SEQ = "actSeq";
	public static final String MONEY = "money";
	public static final String ASCS = "ascs";

	// do not instantiate
	private HeadersKN(){}

	static String keyTwoOf( String str ) {
		return str + "_r";
	}
	static String deltaOf( String str ) {
		return "d_" + str;
	}
}
