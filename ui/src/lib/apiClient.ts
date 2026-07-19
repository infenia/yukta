// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export interface ApiResponse<T> {
  timestamp: string;
  status: number;
  message: string;
  data: T;
  error?: string;
  path: string;
  errors?: Record<string, string[]>;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public error?: string,
    public fieldErrors?: Record<string, string[]>
  ) {
    super(error || `API error: ${status}`);
    this.name = 'ApiError';
  }
}

export async function fetchApi<T>(
  path: string,
  options?: RequestInit
): Promise<T> {
  const response = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  });

  let json: ApiResponse<T>;
  try {
    json = (await response.json()) as ApiResponse<T>;
  } catch (e) {
    throw new ApiError(
      response.status,
      `Failed to parse JSON: ${e instanceof Error ? e.message : String(e)}`
    );
  }

  if (json.status !== 200 && json.status !== 201 && json.status !== 202) {
    throw new ApiError(json.status, json.error, json.errors);
  }

  if (!response.ok) {
    throw new ApiError(
      response.status,
      json.error || `HTTP ${response.status}`,
      json.errors
    );
  }

  return json.data;
}
