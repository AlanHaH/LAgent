export interface ApiEnvelope<T> { success: boolean; data: T; requestId: string }
export interface PageResult<T> { items?: T[]; records?: T[]; total: number; page: number; pageSize: number }
export interface UserView { publicId: string; username: string; email: string; timezone: string; emailVerified: boolean; roles: string[]; permissions: string[]; version: number }
export interface TokenPair { accessToken: string; refreshToken: string; expiresIn: number; user: UserView }
export type EmailVerificationPurpose = 'REGISTER' | 'PASSWORD_RESET'
export interface EmailCodeDelivery { expiresInSeconds: number; resendAfterSeconds: number }
export interface Entity { id?: number; publicId: string; version: number; createdAt?: string; updatedAt?: string; [key: string]: unknown }
