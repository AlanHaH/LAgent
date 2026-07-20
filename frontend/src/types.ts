export interface ApiEnvelope<T> { success: boolean; data: T; requestId: string }
export interface PageResult<T> { items?: T[]; records?: T[]; total: number; page: number; pageSize: number }
export interface UserView { publicId: string; username: string; email: string; timezone: string; roles: string[]; permissions: string[]; version: number }
export interface TokenPair { accessToken: string; refreshToken: string; expiresInSeconds: number; user: UserView }
export interface Entity { id?: number; publicId: string; version: number; createdAt?: string; updatedAt?: string; [key: string]: unknown }
