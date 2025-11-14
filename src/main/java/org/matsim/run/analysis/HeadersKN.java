package org.matsim.run.analysis;

class HeadersKN{
	public static String MODE_SEQ ="modeSeq";
	public static String PERSON_ID = "personId";
	public static String INCOME = "income";
	public static String SCORE = "score[u]";
	public static String BENEFIT = "wtp4score";
	public static String UTL_OF_MONEY = "utlOfMoney";
	public static String TTIME = "ttime[h]";
	public static String ACT_SEQ = "actSeq";
	public static String MONEY = "money";
	public static String ASCS = "ascs";
	public static String STUCK = "stuck";

	// do not instantiate
	private HeadersKN(){}

	static String keyTwoOf( String str ) {
		return str + "_r";
	}
	static String deltaOf( String str ) {
		return "d_" + str;
	}
}
