library(tidyverse)

prices <- read.csv(file="../../shared-svn/projects/DiTriMo/data/deutschlandtarif_prices/deutschlandtarif_prices.csv")

prices20 <- prices %>% 
  filter(km <= 20)

prices100 <- prices %>% 
  filter(km <= 100)

prices1000 <- prices %>% 
  filter(km <= 1000)

pricesAbove100 <- prices %>% 
  filter(km >= 100)

linear20 <- lm(price ~ km, data = prices20)
linear100 <- lm(price ~ km, data = prices100)
linear1000 <- lm(price ~ km, data = prices1000)
linear <- lm(price ~ km, data = prices)
linearAbove100 <- lm(price ~ km, data = pricesAbove100)

# Create a dataframe for the lines
lines_df <- data.frame(
  intercept = c(coef(linear)[1], coef(linear20)[1], coef(linear100)[1], coef(linear1000)[1], coef(linearAbove100)[1]),
  slope = c(coef(linear)[2], coef(linear20)[2], coef(linear100)[2], coef(linear1000)[2], coef(linearAbove100)[2]),
  label = c("1-2000km", "1-20km", "1-100km", "1-1000km", "100-2000km")
)

ggplot(prices, aes(x = km, y = price)) +
  geom_point() +  # Scatter plot of the data points
  geom_abline(data = lines_df, aes(intercept = intercept, slope = slope, color = label), linewidth=.75) + 
  scale_color_manual(values = c("red", "lightblue", "orange3", "purple4", "pink")) + # Manually setting colors
  labs(title = "Deutschlandtarif linear functions",
       x = "km",
       y = "price [€]",
       color = "Model") +  # Legend title
  theme_minimal() 

lines_df_relevant <- data.frame(
  intercept = c(coef(linear100)[1], coef(linearAbove100)[1]),
  slope = c(coef(linear100)[2], coef(linearAbove100)[2]),
  label = c("1-100km", "100-2000km")
)

ggplot(prices, aes(x = km, y = price)) +
  geom_point() +  # Scatter plot of the data points
  geom_abline(data = lines_df_relevant, aes(intercept = intercept, slope = slope, color = label), linewidth=.75) + 
  scale_color_manual(values = c("red", "blue"), labels = c("1-100km y = 0.272x + 1.67", "100-2000km y = 0.11x + 26.89")) + # Manually setting colors
  labs(title = "Deutschlandtarif linear functions",
       x = "km",
       y = "price [€]",
       color = "Model") +  # Legend title
  theme_minimal()
