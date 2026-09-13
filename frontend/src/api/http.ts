import axios from 'axios'

export const TOKEN_KEY = 'medical-cloud-token'
export const http = axios.create()

http.interceptors.request.use(config => {
  const token = sessionStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) sessionStorage.removeItem(TOKEN_KEY)
  return Promise.reject(error)
})
