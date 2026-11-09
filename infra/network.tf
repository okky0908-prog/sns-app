# --- ALB 用のプライベートサブネット（Issue #161）---
# CloudFront の VPC オリジンは、オリジン（ALB）がプライベートサブネットにあることが前提（docs/infrastructure.md「HTTPS」）。
# 既定の VPC のサブネットはどれもインターネットへの経路があるパブリックサブネットなので、ALB 用に2つ作る。
# Fargate のタスクと RDS は、今までどおり既定のサブネットに置く（タスクは NAT ゲートウェイなしで ECR などへ出るため）

# インターネットゲートウェイへの経路を持たないルートテーブル（VPC の中への経路だけ）
resource "aws_route_table" "private" {
  vpc_id = data.aws_vpc.default.id

  tags = {
    Name = "${var.project_name}-private-rt"
  }
}

# 既定のサブネット（172.31.0.0/20 など）と重ならないよう、VPC の後ろ半分から /24 を使う（172.31.128.0/24・172.31.129.0/24）。
# ALB と VPC オリジンのネットワークの口（ENI）が、ここにプライベート IP を持つ
resource "aws_subnet" "private" {
  count = length(local.alb_azs)

  vpc_id                  = data.aws_vpc.default.id
  availability_zone       = local.alb_azs[count.index]
  cidr_block              = cidrsubnet(data.aws_vpc.default.cidr_block, 8, 128 + count.index)
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.project_name}-private-${local.alb_azs[count.index]}"
  }
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
