// API のレスポンスの型（docs/api.md・バックエンドの UserResponse / AuthResponse / ApiError と同じ形）

export interface UserSummary {
  id: number
  username: string
  displayName: string
  iconUrl: string | null
}

export interface AuthResponse {
  token: string
  user: UserSummary
}

export interface FieldErrorBody {
  field: string
  message: string
}

export interface ApiErrorBody {
  status: number
  message: string
  errors: FieldErrorBody[]
}

export interface SignupInput {
  username: string
  displayName: string
  email: string
  password: string
}

export interface LoginInput {
  email: string
  password: string
}
