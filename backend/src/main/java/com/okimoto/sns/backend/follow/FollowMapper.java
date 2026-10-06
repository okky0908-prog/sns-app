package com.okimoto.sns.backend.follow;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** follows テーブルへのアクセス。SQL は resources/mapper/FollowMapper.xml に書く。フォローの画面は今後実装する。 */
@Mapper
public interface FollowMapper {

  /** そのユーザーをフォローしている人の ID（リアルタイム通知で「フォロー中タブに出すか」を決めるのに使う）。 */
  List<Long> findFollowerIds(long followeeId);
}
