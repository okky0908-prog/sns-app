# --- 請求アラート（AWS Budgets）---
# ALB は止められず、存在するだけで課金される。消し忘れに気づけるよう、月の料金が予算を超えそう・超えたらメールで知らせる
# （docs/infrastructure.md「費用」）

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project_name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # 月末までの見込みが予算を超えそうなとき
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_alert_email]
  }

  # 実際の料金が予算の 80% を超えたとき
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_alert_email]
  }
}
