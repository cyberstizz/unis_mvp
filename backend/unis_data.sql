--
-- PostgreSQL database dump
--

-- Dumped from database version 16.4
-- Dumped by pg_dump version 16.4

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: genres; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.genres VALUES ('00000000-0000-0000-0000-000000000101', 'Hip-Hop', '2025-10-15 00:45:17.501721');
INSERT INTO public.genres VALUES ('00000000-0000-0000-0000-000000000102', 'R&B', '2025-10-15 00:45:17.501721');
INSERT INTO public.genres VALUES ('00000000-0000-0000-0000-000000000103', 'Rock', '2025-10-15 00:45:17.501721');


--
-- Data for Name: jurisdictions; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.jurisdictions VALUES ('00000000-0000-0000-0000-000000000001', 'Harlem', NULL, NULL, 'The heart of Harlem, pulsing with rhythm from 110th to 155th Street—home to legends and new voices.', '2025-10-15 00:46:49.980444', '/uploads/harlem-symbol.jpg');
INSERT INTO public.jurisdictions VALUES ('00000000-0000-0000-0000-000000000002', 'Uptown Harlem', NULL, '00000000-0000-0000-0000-000000000001', 'Uptown vibes from the Apollo to Sugar Hill, where jazz meets hip-hop.', '2025-10-15 00:46:49.980444', '/uploads/uptown-symbol.jpg');
INSERT INTO public.jurisdictions VALUES ('00000000-0000-0000-0000-000000000003', 'Downtown Harlem', NULL, '00000000-0000-0000-0000-000000000001', 'Downtown energy, blending street art and underground beats south of 110th.', '2025-10-15 00:46:49.980444', '/uploads/downtown-symbol.jpg');


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.users VALUES ('00000000-0000-0000-0000-000000000301', 'hiphop_artist1', 'artist1@unis.com', '$2b$12$G4zXA3uECbq1F2yPbk9LBeuWmi9zbSSI3PiBNvK3YSC06yFCMQfA.', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 16, NULL, NULL, '2025-10-15 07:19:01.91358', 'silver', '00000000-0000-0000-0000-000000000103', NULL);
INSERT INTO public.users VALUES ('fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'rapking', 'rapking@unis.com', '$2b$12$G4zXA3uECbq1F2yPbk9LBeuWmi9zbSSI3PiBNvK3YSC06yFCMQfA.', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 2778, '/uploads/rapkingpic.jpg', NULL, '2025-10-25 18:51:56.993315', 'diamond', '00000000-0000-0000-0000-000000000101', '961630cb-316e-4b23-b945-fdbc22d92d62');
INSERT INTO public.users VALUES ('d03f8c3c-95d2-49a1-a34c-587e26967e0a', 'stizz', 'stizz@unis.com', '$2a$10$sKYiGj0FD7JR2crf8S87yuEa1Im7kUd7IA2y1jyqrQUGxaSut9CGO', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 67, '/uploads/7e23fa59-2188-4057-9ec9-be8d45254444-1763637852372.jpg', 'testing', '2025-11-20 04:24:27.807187', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('146cf18e-93a5-439a-baac-60e46915c8af', 'fan_one', 'fan_one@unis.com', '$2a$10$GggUY8qSmfVaKObbFLHZ6eKJi1lv82KCOi90XueZizVRTZq8UV39O', 'listener', '15c399c4-546f-4a99-8d1a-1170c0576744', '00000000-0000-0000-0000-000000000002', 537, NULL, NULL, '2025-10-25 19:06:02.061219', 'platinum', NULL, NULL);
INSERT INTO public.users VALUES ('969d0330-9b7e-4700-a621-9912888d91c7', 'testlistener3', 'test3@unis.com', '$2a$10$fI8Xy3XHS0L4sRixEYPVfOTgkKA..OdNUVPcS7/b8xfT2dvLwgZoe', 'listener', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 41, NULL, NULL, '2025-10-16 04:28:58.107327', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('6cbc85ea-b33a-4cef-8499-f02e485cf068', 'snubcheesy', 'snubcheesy@unis.com', '$2a$10$y4OVGfiU3iTE7I4KCjQzDuk7KKC2zZc9uNPueC2hPKj0tKpQHmdIa', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 04:19:55.563268', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('e6e1b9a7-c721-40ff-9cf0-419d236c6135', 'fan_three', 'fan_three@unis.com', '$2a$10$/b23WrYSnYMpPZWgGpRox.Jwo9/QI7I8Ua3tZiB6qEXW770qWZRl2', 'listener', '674b09b8-eff2-4c21-b86b-156152026943', '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-10-25 19:10:53.083926', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('136cdf87-39c2-4561-997e-b530b07f83e1', 'testlistener2', 'test2@unis.com', '$2a$10$MFYhLZk2uYoRI0MSsa0Lhu1n.6vJLPQ3eqf9lFojtesbbqfHWb1su', 'listener', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 16, NULL, NULL, '2025-10-15 09:17:41.806463', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('d9679a27-0996-45ce-96ab-4bc2cb89b7a9', 'c-money', 'c-money@unis.com', '$2a$10$aQLH4lSFhQCMtesF2XUWyO0B/IQmWd9C2LWZ1pCvWRD8TU8VQPRo.', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 07:44:52.303321', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('c09851a8-e6b5-4fc1-aa49-cc9a62b55eaa', 'fan_four', 'fan_four@unis.com', '$2a$10$53evd0NVpMOj9dn8IEJB5.UuO.o7abWwHPL4UJMnNOzZwwCUcE72q', 'listener', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', '00000000-0000-0000-0000-000000000003', 0, NULL, NULL, '2025-10-25 19:12:58.70266', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('a7748c13-8f55-4078-a67b-4cb42e724d52', 'rockle_gend', 'rockle_gend@unis.com', '$2b$12$G4zXA3uECbq1F2yPbk9LBeuWmi9zbSSI3PiBNvK3YSC06yFCMQfA.', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 135, '/uploads/rocklegendpic.jpeg', NULL, '2025-10-25 18:56:30.762962', 'gold', '00000000-0000-0000-0000-000000000102', 'f7f158a1-9772-4dde-8c44-770ca7ef7439');
INSERT INTO public.users VALUES ('8ccff8ad-1d05-45d2-b326-83d7e158e735', 'testlistener4', 'test4@unis.com', '$2a$10$d/5cwXdNRzykYCn2I8jIzuTztYPhAvgPf.FzBF4j0IQuWtEkk4RJe', 'listener', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 41, '/uploads/songartworkfour.jpg', 'Updated bio for test user', '2025-10-16 04:37:51.504775', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('1e75659d-3d2f-453a-ab28-d237f2caba0c', 'cool-c', 'cool-c@unis.com', '$2a$10$8T6xKxxyu5HCEhNRgCs7Gucgxxd0bHt84f8r8hS6IR7AcpWiWs9Gu', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 16, NULL, NULL, '2025-11-20 07:22:23.769066', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('674b09b8-eff2-4c21-b86b-156152026943', 'Lyricalqueen', 'Lyricalqueen@unis.com', '$2b$12$G4zXA3uECbq1F2yPbk9LBeuWmi9zbSSI3PiBNvK3YSC06yFCMQfA.', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 103, '/uploads/lyricalqueenpic.jpeg', NULL, '2025-10-25 18:55:08.418839', 'gold', '00000000-0000-0000-0000-000000000101', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76');
INSERT INTO public.users VALUES ('32024cc2-f40a-43fc-b161-1bd67f8373b8', 'newfan', 'newfan@unis.com', '$2a$10$RSyiSlfLZKBo1.siclkzFuDkTTxzHpSyLIsktiWXCeD/QKt/sSpRG', 'listener', 'a7748c13-8f55-4078-a67b-4cb42e724d52', '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-10-30 15:36:49.705408', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('15c399c4-546f-4a99-8d1a-1170c0576744', 'Hiphop_artist1', 'Hiphop_artist1@unis.com', '$2b$12$G4zXA3uECbq1F2yPbk9LBeuWmi9zbSSI3PiBNvK3YSC06yFCMQfA.', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 3, '/uploads/hiphopatist1.jpg', NULL, '2025-10-25 18:57:21.566075', 'silver', '00000000-0000-0000-0000-000000000101', '607d94f7-e5b0-46ab-9e63-464a4046cac9');
INSERT INTO public.users VALUES ('ae4ea901-7bb1-4e8c-9366-d9410ef6e330', 'testlistener1', 'test1@unis.com', '$2a$10$M0LSJri.HzwtXTSIn9lQkOs3Yo1m/YPDcAIppWf07/mHABtVq.3x6', 'listener', '00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001', 16, NULL, NULL, '2025-10-15 07:20:39.366698', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('4c0be84f-7c38-4a9a-b71b-1c7d7fa1215f', 'blue', 'blue@unis.com', '$2a$10$/0eD.li/79qefwZGOFjmWeavnbgJTPuIQ7RDhPh1mT19z8PS/czC6', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 22:23:49.920244', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('beba37ed-67e0-4684-8218-2c31e3813aad', 'wind', 'wind@unis.com', '$2a$10$8yhjzb6HNZqQDGdh4ztKEuSKlx90EEj2mmo8sIBnCtLQYo244Eejq', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 11:46:10.997134', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('c927d5af-f8b3-4505-a6dc-31462806aa63', 'orange', 'orange@unis.com', '$2a$10$60jReoaHycUNut1dwVlxwu/T.Z4J2oxu.jgu3.GLu0ihOh7gyJjOO', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 0, NULL, NULL, '2025-11-20 19:37:29.463804', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('e58081fd-cd06-4be0-ae20-65ca218a7e58', 'earth', 'earth@unis.com', '$2a$10$YLr68fKK5WeFsALqL61VaOqfpz6Zxks1l5y76azRRK9SaeK9ebmKu', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 11:58:58.114284', 'silver', '00000000-0000-0000-0000-000000000101', NULL);
INSERT INTO public.users VALUES ('41454ec1-b793-422d-afd4-7c1aaef2ba32', 'multipass', 'multipass@unis.com', '$2a$10$DbO6Si3A6UzHGDo5uRgZpuiQo/thvMWwxC2l8LPsG0RifW2tSoAeC', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 12:05:13.224901', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('c9905660-4f0a-4da7-a59d-8c9b8e67bef4', 'Beast', 'beast@unis.com', '$2a$10$XmeBMhqOz1QdSdDoBmhjRObDbWGqDLjUWZP9sCjEW9sV0hgJ8j0w6', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 0, NULL, NULL, '2025-11-20 08:05:36.760143', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('b41340ed-14ea-4c71-901c-817c409b04c5', 'fan_two', 'fan_two@unis.com', '$2a$10$1fMTcJGlV51ezoes11oNjeNI5dv9YT0et.7tjBq1Fp/H4NPuNWCgK', 'listener', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', '00000000-0000-0000-0000-000000000003', 0, NULL, NULL, '2025-10-25 19:08:26.15993', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('b2528665-7e4b-4035-8487-a35f9b5a65af', 'fire', 'fire@unis.com', '$2a$10$fgpizVfODUvCQGhssPUJFeyTdKrREhZMzo9aygZH/LkhwZ1nsnjMe', 'artist', NULL, '00000000-0000-0000-0000-000000000003', 0, NULL, NULL, '2025-11-20 11:41:10.438239', 'silver', NULL, NULL);
INSERT INTO public.users VALUES ('56fb8676-7101-4eb7-9c60-495f92e17db9', 'water', 'water@unis.com', '$2a$10$WN0yuCJ/9LcMyRD/02Jxs.jpmDgGtB1d0Pzs433gyL7cCDZl4W7.e', 'artist', NULL, '00000000-0000-0000-0000-000000000002', 0, NULL, NULL, '2025-11-20 11:31:22.806208', 'silver', '00000000-0000-0000-0000-000000000101', NULL);


--
-- Data for Name: ad_views; Type: TABLE DATA; Schema: public; Owner: unis_user
--



--
-- Data for Name: voting_intervals; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.voting_intervals VALUES ('00000000-0000-0000-0000-000000000201', 'Daily', 1, '2025-10-15 00:45:26.109606');
INSERT INTO public.voting_intervals VALUES ('00000000-0000-0000-0000-000000000202', 'Weekly', 7, '2025-10-15 00:45:26.109606');
INSERT INTO public.voting_intervals VALUES ('00000000-0000-0000-0000-000000000203', 'Monthly', 30, '2025-10-15 00:45:26.109606');
INSERT INTO public.voting_intervals VALUES ('00000000-0000-0000-0000-000000000204', 'Quarterly', 90, '2025-10-15 00:45:26.109606');
INSERT INTO public.voting_intervals VALUES ('00000000-0000-0000-0000-000000000205', 'Annual', 365, '2025-10-15 00:45:26.109606');


--
-- Data for Name: awards; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.awards VALUES ('87ce0ab1-1216-4c91-a98f-be51f2d62339', 'artist', '674b09b8-eff2-4c21-b86b-156152026943', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000201', '2025-11-20', 1, 10, 100, NULL, NULL);


--
-- Data for Name: likes; Type: TABLE DATA; Schema: public; Owner: unis_user
--



--
-- Data for Name: referrals; Type: TABLE DATA; Schema: public; Owner: unis_user
--



--
-- Data for Name: songs; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.songs VALUES ('607d94f7-e5b0-46ab-9e63-464a4046cac9', '15c399c4-546f-4a99-8d1a-1170c0576744', 'fire', '00000000-0000-0000-0000-000000000101', '/uploads/4eba2ed5-338b-4105-bfa7-b50628d21d5a-1762729433893.mp3', 281, 'extreme blazing fires EVERYWHERE!', 180000, '2025-11-09 18:03:53.946953', 'gold', '/uploads/b2f10889-142f-417d-bcb9-84d3cfef8a01-1762729433920.jpg', '00000000-0000-0000-0000-000000000003');
INSERT INTO public.songs VALUES ('c731cb58-7744-4153-ac4b-1b8d1fc12e36', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'oatmeal', '00000000-0000-0000-0000-000000000101', '/uploads/ef2f5595-7db5-40d0-8bb5-12d492ae1f57-1762578879660.mp3', 222, 'some good oatmeal', 180000, '2025-11-08 00:14:39.706494', 'gold', '/uploads/471b5107-82a1-4d2f-a813-1b5e90ec5641-1762578879678.jpg', '00000000-0000-0000-0000-000000000003');
INSERT INTO public.songs VALUES ('f7f158a1-9772-4dde-8c44-770ca7ef7439', 'a7748c13-8f55-4078-a67b-4cb42e724d52', 'rock baby', '00000000-0000-0000-0000-000000000101', '/uploads/ca080953-9512-4d17-9153-fcc0c79ac279-1762729824763.mp3', 155, 'you will be fast asleep after this song', 180000, '2025-11-09 18:10:24.784468', 'gold', '/uploads/298cf506-1853-406f-85f3-22a4a6de57bf-1762729824782.jpeg', '00000000-0000-0000-0000-000000000002');
INSERT INTO public.songs VALUES ('961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'bomb diggity', '00000000-0000-0000-0000-000000000101', '/uploads/a7daec8e-97ab-4beb-8e1f-9d9c37c01adf-1762467563606.mp3', 883, 'this song is the literal bomb diggity!', 180000, NULL, 'platinum', '/uploads/878feacf-a8ad-46bd-b0e0-3c666e7b5e86-1762467563636.jpg', '00000000-0000-0000-0000-000000000003');
INSERT INTO public.songs VALUES ('e9d8d094-65b5-445f-bef0-cb8fe3e69c76', '674b09b8-eff2-4c21-b86b-156152026943', 'Big booty sells', '00000000-0000-0000-0000-000000000101', '/uploads/fabb33be-4cc6-47c2-b2a6-e6749d8e49f5-1762479308913.mp3', 1028, 'I provide it you buy it. Boom!', 180000, NULL, 'diamond', '/uploads/a5e619c6-64a3-48ba-bfa2-17b0d99caefd-1762479308945.jpg', '00000000-0000-0000-0000-000000000003');
INSERT INTO public.songs VALUES ('286f70ea-7e83-4a3d-955f-3117cd0af40a', 'd03f8c3c-95d2-49a1-a34c-587e26967e0a', 'bump this feat. T.C.', '00000000-0000-0000-0000-000000000101', '/uploads/daf18a46-5645-4368-9e8e-98efc634d1a3-1763633022105.mp3', 0, 'NRC era banger', 180000, '2025-11-20 05:03:42.162413', 'silver', '/uploads/4d6b0c3c-cc15-4c76-912d-2af4fb2a27cb-1763633022122.jpg', '00000000-0000-0000-0000-000000000002');
INSERT INTO public.songs VALUES ('621e322c-3b30-487e-aa7c-093119ba12f3', '00000000-0000-0000-0000-000000000301', 'pop off', '00000000-0000-0000-0000-000000000101', '/uploads/a0db4308-7be5-4578-b179-4d86be4a9a2b-1762730102447.mp3', 0, 'this song will make you fight EVERYONE IN THE CLUB!!!!', 180000, '2025-11-09 18:15:02.487756', 'silver', '/uploads/fe84e4c5-22af-4ebd-97d9-109d9de6eafc-1762730102486.jpeg', '00000000-0000-0000-0000-000000000002');


--
-- Data for Name: song_plays; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.song_plays VALUES ('433e95a2-6109-49a6-a42f-945189037bb8', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('82391e7f-a072-4909-b016-0317b35d0360', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('2ab8867e-80d5-4bb4-a7bb-63a276ba1488', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('d753b06d-92cb-4297-b483-4df072c3ff78', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('88ea278b-6d89-4ee5-a039-3e8a8f2196ee', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('9625c249-2702-473f-9211-3b072dcbc310', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('8641c206-48e0-4e7e-a879-571720c74b5a', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('db185124-dd26-47fb-85f4-54e7a1fa141c', 'f7f158a1-9772-4dde-8c44-770ca7ef7439', 'a7748c13-8f55-4078-a67b-4cb42e724d52', NULL, 180);
INSERT INTO public.song_plays VALUES ('800b5ea8-f236-4c3d-952f-3f98ebe67af2', '607d94f7-e5b0-46ab-9e63-464a4046cac9', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('04d59735-1f83-4ca9-9e14-e7bc8899c2a3', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('56d32e7d-8861-482c-91a9-fbd9eb3cfa7b', 'c731cb58-7744-4153-ac4b-1b8d1fc12e36', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('fe24fb4b-80d1-43f3-94dc-20afecf8bd79', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('d2ade6f1-6072-464d-ac4a-5f2dbf661476', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('585063a0-6811-4062-99e7-5a11fbd63e5d', 'c731cb58-7744-4153-ac4b-1b8d1fc12e36', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('65354244-0fcd-4a90-9d8f-8f6b2cd84cd5', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('ede73802-3381-47c0-aacd-e509fcd4bf10', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('6b234945-0e22-46df-9107-af82aa9c7607', '607d94f7-e5b0-46ab-9e63-464a4046cac9', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('ddf168b7-37d9-466b-baa8-f12e7db65a3a', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('3108c2b8-8363-4b4c-9ce8-9cb9c1a136ac', '607d94f7-e5b0-46ab-9e63-464a4046cac9', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('6e0f8fba-2a36-4890-9f42-144845a030d0', '961630cb-316e-4b23-b945-fdbc22d92d62', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('858f4e21-f9e9-4d6a-b409-0f3ce4b05a93', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.song_plays VALUES ('41968486-b808-4896-a00f-70365ec9e0a4', 'f7f158a1-9772-4dde-8c44-770ca7ef7439', 'd03f8c3c-95d2-49a1-a34c-587e26967e0a', NULL, 180);
INSERT INTO public.song_plays VALUES ('e7851fbe-40aa-4be3-9cad-0301e59227f0', '607d94f7-e5b0-46ab-9e63-464a4046cac9', '1e75659d-3d2f-453a-ab28-d237f2caba0c', NULL, 180);


--
-- Data for Name: supporters; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.supporters VALUES ('1abaaa99-1446-45b7-a403-1e3e6a6a791a', 'ae4ea901-7bb1-4e8c-9366-d9410ef6e330', '00000000-0000-0000-0000-000000000301', NULL);
INSERT INTO public.supporters VALUES ('947d54c3-d05e-4eb9-b10b-cdb57ade7d6e', '136cdf87-39c2-4561-997e-b530b07f83e1', '00000000-0000-0000-0000-000000000301', NULL);
INSERT INTO public.supporters VALUES ('75de7005-780a-4005-aa56-f76246696e7c', '969d0330-9b7e-4700-a621-9912888d91c7', '00000000-0000-0000-0000-000000000301', NULL);
INSERT INTO public.supporters VALUES ('c3a6572d-3643-42c3-bb13-9321be34ec69', '8ccff8ad-1d05-45d2-b326-83d7e158e735', '00000000-0000-0000-0000-000000000301', NULL);
INSERT INTO public.supporters VALUES ('37747838-ff5f-43d6-b246-870457d05b97', '146cf18e-93a5-439a-baac-60e46915c8af', '15c399c4-546f-4a99-8d1a-1170c0576744', NULL);
INSERT INTO public.supporters VALUES ('a07af22a-63a2-4eb2-9033-b4e92d72f266', 'b41340ed-14ea-4c71-901c-817c409b04c5', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL);
INSERT INTO public.supporters VALUES ('96390fc4-87b9-4e76-8484-01c878a653e7', 'e6e1b9a7-c721-40ff-9cf0-419d236c6135', '674b09b8-eff2-4c21-b86b-156152026943', NULL);
INSERT INTO public.supporters VALUES ('436bca67-28ac-45dc-b27a-f1ab010f61e2', 'c09851a8-e6b5-4fc1-aa49-cc9a62b55eaa', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL);
INSERT INTO public.supporters VALUES ('54339390-e5cb-4321-a9f7-9fdfc5e2ed2d', '32024cc2-f40a-43fc-b161-1bd67f8373b8', 'a7748c13-8f55-4078-a67b-4cb42e724d52', NULL);


--
-- Data for Name: videos; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.videos VALUES ('d443d259-f52f-4b1a-b081-bb6acd4acf66', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', '00000000-0000-0000-0000-000000000101', 'Rapking Video 1', '/uploads/27792122-8123-47c4-8fdc-38f35e102eed-1761454088076.mp4', 'First rap video', 300, 0, NULL, NULL, NULL, '00000000-0000-0000-0000-000000000003');
INSERT INTO public.videos VALUES ('01a7e3b2-8009-44fc-a5c4-7b28633a9fdf', '15c399c4-546f-4a99-8d1a-1170c0576744', '00000000-0000-0000-0000-000000000101', 'Hiphop Video 1', '/uploads/3abb6e03-68aa-4399-9842-c65647d8d44a-1761454766745.mp4', 'Hip hop banger', 200, 0, NULL, NULL, NULL, '00000000-0000-0000-0000-000000000003');
INSERT INTO public.videos VALUES ('e08d159e-e0ed-46bd-8d03-d7e5d03ae428', 'a7748c13-8f55-4078-a67b-4cb42e724d52', '00000000-0000-0000-0000-000000000101', 'Rockle Video 1', '/uploads/5a31af6e-54e8-4f9a-a143-71461699583a-1761454395800.mp4', 'Rockle video track', 280, 0, NULL, NULL, NULL, '00000000-0000-0000-0000-000000000002');
INSERT INTO public.videos VALUES ('5af97a37-8ac5-4654-88b7-4280963fde13', '674b09b8-eff2-4c21-b86b-156152026943', '00000000-0000-0000-0000-000000000101', 'Lyricalqueen Video 1', '/uploads/32d6d62c-1d2e-4a81-834e-1a3bec2b524f-1761454257580.mp4', 'Queen video flow', 320, 0, NULL, NULL, NULL, '00000000-0000-0000-0000-000000000003');
INSERT INTO public.videos VALUES ('f38db306-95e8-4642-a368-fbc37a645e30', 'd03f8c3c-95d2-49a1-a34c-587e26967e0a', '00000000-0000-0000-0000-000000000101', 'a dumb vid', '/uploads/14b2fffb-cfe8-47fd-bdad-b2f2a778b29d-1763636704684.mp4', 'a dumb description', 180000, 0, '2025-11-20 06:05:04.845379', 'silver', NULL, '00000000-0000-0000-0000-000000000002');


--
-- Data for Name: video_plays; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.video_plays VALUES ('dffb1410-3e0e-42e5-8a80-64b1c07d6c94', '5af97a37-8ac5-4654-88b7-4280963fde13', '146cf18e-93a5-439a-baac-60e46915c8af', NULL, 180);
INSERT INTO public.video_plays VALUES ('1a0034bd-eb5d-4a7a-a314-841660cc4590', '5af97a37-8ac5-4654-88b7-4280963fde13', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', NULL, 180);
INSERT INTO public.video_plays VALUES ('bfee95c8-3eeb-4f2f-a158-86223a0da117', 'f38db306-95e8-4642-a368-fbc37a645e30', 'd03f8c3c-95d2-49a1-a34c-587e26967e0a', NULL, 180);


--
-- Data for Name: votes; Type: TABLE DATA; Schema: public; Owner: unis_user
--

INSERT INTO public.votes VALUES ('0e2756dc-f6b6-45cc-a782-2df85b22df88', '146cf18e-93a5-439a-baac-60e46915c8af', 'song', '43f3c10d-f01e-4ef9-a8dc-6b2278567504', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000201', '2025-10-26', NULL);
INSERT INTO public.votes VALUES ('e74c4a4c-5341-4a40-84a1-ff3fa47e8a98', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'artist', '15c399c4-546f-4a99-8d1a-1170c0576744', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000201', '2025-11-12', NULL);
INSERT INTO public.votes VALUES ('599273ec-3ebc-4ba7-9ff0-3a00be650fee', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'artist', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000201', '2025-11-15', NULL);
INSERT INTO public.votes VALUES ('af5250d7-11f8-44c1-8e63-4a025e57911b', 'fb89ce0a-a9bc-4787-85d7-3a9b97f75400', 'song', 'e9d8d094-65b5-445f-bef0-cb8fe3e69c76', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000201', '2025-11-16', NULL);
INSERT INTO public.votes VALUES ('c0fea104-6fac-4a6e-ae14-04fdd1de6a13', 'd03f8c3c-95d2-49a1-a34c-587e26967e0a', 'artist', '674b09b8-eff2-4c21-b86b-156152026943', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000201', '2025-11-20', NULL);


--
-- PostgreSQL database dump complete
--

