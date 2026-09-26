-- VGContact MVP - Complete Supabase Schema
-- Copy-paste into Supabase SQL Editor

-- Users Table
CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  android_id TEXT UNIQUE NOT NULL,
  email TEXT UNIQUE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  total_downloads INTEGER DEFAULT 0,
  total_reposts INTEGER DEFAULT 0,
  subscription_status TEXT DEFAULT 'free'
);
CREATE INDEX idx_users_email ON users(email);

-- Files Table
CREATE TABLE IF NOT EXISTS files (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  file_name TEXT NOT NULL,
  contact_count INTEGER NOT NULL,
  file_url TEXT NOT NULL,
  file_category TEXT DEFAULT 'Other',
  is_published BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_files_published ON files(is_published);

-- Reposts Table
CREATE TABLE IF NOT EXISTS reposts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
  status TEXT DEFAULT 'pending',
  unlock_code TEXT UNIQUE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP + INTERVAL '24 hours'
);
CREATE INDEX idx_reposts_user ON reposts(user_id);

-- Unlocks Table
CREATE TABLE IF NOT EXISTS unlocks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
  unlocked_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_unlocks_user ON unlocks(user_id);

-- Activity Logs Table
CREATE TABLE IF NOT EXISTS activity_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  action TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Notifications Table
CREATE TABLE IF NOT EXISTS notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  is_sent BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Row Level Security
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE files ENABLE ROW LEVEL SECURITY;
ALTER TABLE reposts ENABLE ROW LEVEL SECURITY;
ALTER TABLE unlocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

-- RLS Policies
CREATE POLICY "Users can view own record" ON users FOR SELECT USING (auth.uid() = id);
CREATE POLICY "Files are public" ON files FOR SELECT USING (is_published = true);
CREATE POLICY "Users can view own reposts" ON reposts FOR SELECT USING (user_id = auth.uid());
CREATE POLICY "Users can create reposts" ON reposts FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY "Users can view own unlocks" ON unlocks FOR SELECT USING (user_id = auth.uid());
CREATE POLICY "Users can create unlocks" ON unlocks FOR INSERT WITH CHECK (user_id = auth.uid());

-- Sample Data
INSERT INTO files (file_name, contact_count, file_url, file_category, is_published)
VALUES (
  'Business_Contacts_Sept26.vcf',
  150,
  'https://example.com/files/business.vcf',
  'Business',
  TRUE
);

INSERT INTO files (file_name, contact_count, file_url, file_category, is_published)
VALUES (
  'Social_Influencers_Sept26.vcf',
  250,
  'https://example.com/files/social.vcf',
  'Social',
  TRUE
);

