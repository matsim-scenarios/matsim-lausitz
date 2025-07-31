library(tidyverse)

scoreComparisonNoDrtRefund <- read.csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/runs-no-drt-fare-refund-OLD/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf/analysis/analysis/drt_persons_executed_score.csv")
scoreComparison <- read.csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf/analysis/analysis/drt_persons_executed_score.csv")

scoresBad <- scoreComparison %>% 
  filter(executed_score_policy < executed_score_base) %>% 
  filter (executed_score_base - executed_score_policy >= 1) %>% 
  mutate(person=as.character(person))

scoresBadNoDrtRefund <- scoreComparisonNoDrtRefund %>% 
  filter(executed_score_policy < executed_score_base) %>% 
  filter (executed_score_base - executed_score_policy >= 1) %>% 
  mutate(person=as.character(person))

personsNoDrtRefund <- read.csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/runs-no-drt-fare-refund-OLD/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf/lausitz-10pct.output_persons.csv.gz", sep = ";")
persons <- read.csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/no-pooling-pt-fare/output-2-ruhland-bhf-spremberg-bhf/2-ruhland-bhf-spremberg-bhf.output_persons.csv.gz", sep = ";")

personsNoFreight <- persons %>% 
  filter(!is.na(income))

personsNoFreightNoDrtRefund <- personsNoDrtRefund %>% 
  filter(!is.na(income))

scoresBad <- scoresBad %>% 
  left_join(personsNoFreight, by="person") %>% 
  select(person, executed_score_policy, executed_score_base, age, income) %>% 
  mutate(diff_score = executed_score_policy - executed_score_base)

scoresBadNoDrtRefund <- scoresBadNoDrtRefund %>% 
  left_join(personsNoFreightNoDrtRefund, by="person") %>% 
  select(person, executed_score_policy, executed_score_base, age, income) %>% 
  rename(executed_score_policy_no_drt_refund = executed_score_policy) %>% 
  mutate(diff_score_no_drt_refund = executed_score_policy_no_drt_refund - executed_score_base)

# compare score diff to base case and filter only those who a) have a worse score than base and
# b) have a worse score in case with drt refund than without drt refund.
scoresWorseInNewAndCorrectRefundingCase <- scoresBad %>% 
  inner_join(scoresBadNoDrtRefund, by="person") %>% 
  select(person, income.x, executed_score_base.x, executed_score_policy,
         executed_score_policy_no_drt_refund, diff_score, diff_score_no_drt_refund) %>% 
  filter(diff_score < diff_score_no_drt_refund)

meanIncome <- mean(personsNoFreight$income)

####################################### all policy elements doubled until here #########################################

incomeWorstScorePerson <- 431

examplePersons <- c("994771", "633540")
incomeExamplePersons <- c(431, 1927)

betaMoney <- 1.0

betaMoneyIncomeDependent <- betaMoney * meanIncome/incomeWorstScorePerson

utilChange <- (-7.630427396468772 + -7.785915632356495) * betaMoneyIncomeDependent
fareExamplePersons <- c(sum(-7.630427396468772, -7.644943435313633, 3.807955678811954),
                        sum(-3.0, -3.0, -9.915354745948003, -9.96778309515589, 10.931463198370057))
fareExamplePersonsNoDrtRefund <- c(sum(-7.630427396468772, -7.785915632356495),
                        sum(-3.0, -3.0, -9.895887645852568, -9.895321945746455, 7.947378122820169))
reasonsForWorseScore <- c("arrival at act too late through pt/drt leg",
                          "arrival at act too late through pt leg (instead of ride in case without drt refund)")


# sum(-6.740518765053293, -6.163817656427606, -6.832578534371833, -6.276901927994842, 2.7475283892416904)
# sum(-7.887155313198776, -7.231302679020511, -7.925791099871326, -7.220563213771967, 18.376125656055592)
# sum(-7.887155313198776, -7.231302679020511, -7.925791099871326, -7.220563213771967)

df <- data.frame(
  person = examplePersons,
  income = incomeExamplePersons,
  fare = fareExamplePersons,
  fareNoDrtRefund = fareExamplePersonsNoDrtRefund,
  reasonsForWorseScore = reasonsForWorseScore
) %>% 
  mutate(betaMoneyIncomeDependent = betaMoney * meanIncome/income) %>% 
  mutate(utilChange = betaMoneyIncomeDependent * fare,
         utilChangeNoDrtRefund = betaMoneyIncomeDependent * fareNoDrtRefund)

# conclusion: refund of drt trips works and leads to less agents with worse scores than in base case!
# still there are some agents, which have worse scores than base case and even worse scores than in the
# policy case without drt refund.
# The utility change caused by drt/pt fares for those agents are less negative than in policy case without refunding,
# but agents have delayed legs which leads to durations of 0 seconds for some acts -> worse score than in policy case without refunding.

hist(scoresBad$income)
